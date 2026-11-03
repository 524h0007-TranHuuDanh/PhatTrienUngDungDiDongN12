package com.example.coffeestock.sync

import android.util.Log
import androidx.room.withTransaction
import com.example.coffeestock.data.local.AppDatabase
import com.example.coffeestock.data.local.dao.MovementDao
import com.example.coffeestock.data.local.dao.SyncDao
import com.example.coffeestock.data.local.entity.ConflictEntity
import com.example.coffeestock.data.local.entity.MovementEntity
import com.example.coffeestock.data.remote.ApiService
import com.example.coffeestock.data.remote.dto.PushMovementDto
import com.example.coffeestock.data.remote.dto.PushRequestDto
import com.example.coffeestock.data.remote.dto.PushResponseDto
import com.example.coffeestock.data.remote.dto.RemoteMovementDto
import com.example.coffeestock.util.Clock
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException

enum class SyncResult {
    SUCCESS,
    RETRY,
    FAILURE
}

class SyncEngine(
    private val database: AppDatabase,
    private val movementDao: MovementDao,
    private val syncDao: SyncDao,
    private val apiService: ApiService,
    private val apiKey: String,
    private val pullDataApplier: PullDataApplier
) {

    private val gson = Gson()

    companion object {
        private const val TAG = "CoffeeStockSync"
        private const val PAGE_SIZE = 100

        private val RETRYABLE_ITEM_ERRORS = setOf(
            "RATE_LIMITED",
            "TEMPORARY_UNAVAILABLE",
            "SERVER_ERROR",
            "TIMEOUT"
        )
    }

    suspend fun pushChanges(): SyncResult =
        withContext(Dispatchers.IO) {

            val outbox = movementDao.getOutboxBatch()

            Log.d(
                TAG,
                "Bắt đầu PUSH. Số phiếu chờ gửi: ${outbox.size}"
            )

            for (movement in outbox) {

                val marked = movementDao.markSyncing(
                    movement.id,
                    movement.localRev,
                    movement.mutationId
                )

                if (marked == 0) {
                    Log.d(
                        TAG,
                        "Bỏ qua phiếu ${movement.id}: dữ liệu đã thay đổi"
                    )
                    continue
                }

                try {
                    val requestItem = movement.toPushDto()

                    Log.d(
                        TAG,
                        "Đang PUSH phiếu ${movement.id}, " +
                                "operation=${movement.pendingOp}"
                    )

                    val response = apiService.pushChanges(
                        apiKey = apiKey,
                        request = PushRequestDto(
                            items = listOf(requestItem)
                        )
                    )

                    Log.d(
                        TAG,
                        "PUSH response: HTTP ${response.code()} - " +
                                response.message()
                    )

                    when {

                        response.isSuccessful -> {

                            val result = response.body()
                                ?.results
                                ?.firstOrNull {
                                    it.id == movement.id &&
                                            it.mutationId == movement.mutationId
                                }

                            if (result == null) {
                                Log.e(
                                    TAG,
                                    "Không có kết quả tương ứng cho phiếu " +
                                            movement.id
                                )

                                movementDao.markSyncPending(
                                    movement.id,
                                    movement.localRev,
                                    movement.mutationId
                                )

                                return@withContext SyncResult.RETRY
                            }

                            if (result.ok && result.movement != null) {

                                val markedSynced = movementDao.markSynced(
                                    movementId = movement.id,
                                    expectedLocalRev = movement.localRev,
                                    expectedMutationId = movement.mutationId,
                                    newServerVersion = result.movement.serverVersion
                                )

                                if (markedSynced == 0) {
                                    movementDao.adoptServerVersionAfterConcurrentEdit(
                                        movementId = movement.id,
                                        expectedLocalRev = movement.localRev,
                                        newServerVersion = result.movement.serverVersion
                                    )
                                }

                                Log.d(
                                    TAG,
                                    "PUSH thành công phiếu ${movement.id}"
                                )

                            } else {

                                val errorCode = result.error?.code
                                val errorMessage = result.error?.message

                                Log.w(
                                    TAG,
                                    "PUSH phiếu ${movement.id} bị từ chối: " +
                                            "$errorCode - $errorMessage"
                                )

                                if (errorCode == "VERSION_CONFLICT") {

                                    val serverMovement =
                                        result.error?.currentMovement

                                    if (serverMovement != null) {
                                        resolveConflict(
                                            movement,
                                            serverMovement
                                        )
                                    } else {
                                        saveConflictAndMark(
                                            local = movement,
                                            message = errorMessage
                                                ?: "CONFLICT không có dữ liệu server"
                                        )
                                    }

                                } else if (
                                    errorCode != null &&
                                    errorCode in RETRYABLE_ITEM_ERRORS
                                ) {

                                    movementDao.markSyncPending(
                                        movement.id,
                                        movement.localRev,
                                        movement.mutationId
                                    )

                                    return@withContext SyncResult.RETRY

                                } else {

                                    movementDao.markSyncFailed(
                                        movement.id,
                                        movement.localRev,
                                        movement.mutationId
                                    )
                                }
                            }
                        }

                        response.code() == 409 -> {

                            Log.w(
                                TAG,
                                "Phát hiện xung đột HTTP 409: ${movement.id}"
                            )

                            val errorResponse =
                                parseErrorResponse(response)

                            val conflictResult =
                                errorResponse?.results?.firstOrNull {
                                    it.id == movement.id &&
                                            it.mutationId == movement.mutationId
                                }

                            val serverMovement =
                                conflictResult?.error?.currentMovement

                            if (serverMovement != null) {

                                resolveConflict(
                                    movement,
                                    serverMovement
                                )

                            } else {

                                Log.e(
                                    TAG,
                                    "HTTP 409 nhưng không có current_movement"
                                )

                                saveConflictAndMark(
                                    local = movement,
                                    message = "HTTP 409 nhưng server không trả về current_movement"
                                )
                            }
                        }

                        isRetryableHttpCode(response.code()) -> {

                            Log.w(
                                TAG,
                                "PUSH gặp lỗi tạm thời HTTP ${response.code()}"
                            )

                            movementDao.markSyncPending(
                                movement.id,
                                movement.localRev,
                                movement.mutationId
                            )

                            return@withContext SyncResult.RETRY
                        }

                        else -> {

                            Log.e(
                                TAG,
                                "PUSH thất bại HTTP ${response.code()}: " +
                                        response.message()
                            )

                            movementDao.markSyncFailed(
                                movement.id,
                                movement.localRev,
                                movement.mutationId
                            )
                        }
                    }

                } catch (e: IOException) {

                    Log.e(
                        TAG,
                        "Lỗi kết nối khi PUSH phiếu ${movement.id}: " +
                                e.message,
                        e
                    )

                    movementDao.markSyncPending(
                        movement.id,
                        movement.localRev,
                        movement.mutationId
                    )

                    return@withContext SyncResult.RETRY

                } catch (e: CancellationException) {

                    throw e

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Lỗi xử lý khi PUSH phiếu ${movement.id}: " +
                                e.message,
                        e
                    )

                    movementDao.markSyncFailed(
                        movement.id,
                        movement.localRev,
                        movement.mutationId
                    )

                    return@withContext SyncResult.FAILURE
                }
            }

            Log.d(TAG, "Hoàn thành PUSH")

            SyncResult.SUCCESS
        }

    private fun MovementEntity.toPushDto(): PushMovementDto {
        return PushMovementDto(
            id = id,
            operation = pendingOp,
            mutationId = mutationId,
            baseVersion = serverVersion,
            localRev = localRev,
            productId = productId,
            productName = productName,
            type = type,
            quantity = quantity,
            batchCode = batchCode,
            partner = partner,
            note = note,
            status = status,
            isDeleted = isDeleted,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private suspend fun saveConflict(
        local: MovementEntity,
        message: String,
        server: RemoteMovementDto?
    ) {
        val serverData = server?.let {
            gson.toJson(it)
        } ?: message

        syncDao.insertConflict(
            ConflictEntity(
                movementId = local.id,
                localData = gson.toJson(local),
                serverData = serverData,
                localRev = local.localRev,
                serverVersion = server?.serverVersion ?: 0L,
                resolution = "PENDING_RESOLUTION",
                createdAt = Clock.nowUtc()
            )
        )

        Log.w(
            TAG,
            "Đã lưu thông tin xung đột phiếu ${local.id}"
        )
    }

    private fun parseErrorResponse(
        response: Response<PushResponseDto>
    ): PushResponseDto? {
        return try {
            val json = response.errorBody()?.string()

            if (json.isNullOrBlank()) {
                null
            } else {
                gson.fromJson(
                    json,
                    PushResponseDto::class.java
                )
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Không thể phân tích response lỗi",
                e
            )
            null
        }
    }


    suspend fun pullChanges(): SyncResult =
        withContext(Dispatchers.IO) {

            try {
                var cursor = pullDataApplier.getCurrentCursor()

                Log.d(
                    TAG,
                    "Bắt đầu PULL. Cursor hiện tại: $cursor"
                )

                while (true) {
                    val response = apiService.pullChanges(
                        apiKey = apiKey,
                        afterSeq = cursor,
                        limit = PAGE_SIZE
                    )

                    Log.d(
                        TAG,
                        "PULL response: HTTP ${response.code()} - " +
                                response.message()
                    )

                    if (!response.isSuccessful) {

                        Log.e(
                            TAG,
                            "PULL thất bại HTTP ${response.code()}"
                        )

                        return@withContext if (
                            isRetryableHttpCode(response.code())
                        ) {
                            SyncResult.RETRY
                        } else {
                            SyncResult.FAILURE
                        }
                    }

                    val page = response.body()
                        ?: run {
                            Log.e(TAG, "PULL không có response body")
                            return@withContext SyncResult.RETRY
                        }

                    if (
                        page.hasMore &&
                        (
                                page.nextCursor == null ||
                                        page.nextCursor <= cursor
                                )
                    ) {
                        Log.e(
                            TAG,
                            "PULL cursor không tiến lên. " +
                                    "Cursor hiện tại: $cursor"
                        )

                        return@withContext SyncResult.FAILURE
                    }

                    pullDataApplier.applyPage(page)

                    val newCursor =
                        pullDataApplier.getCurrentCursor()

                    if (page.hasMore && newCursor <= cursor) {
                        Log.e(
                            TAG,
                            "PULL không cập nhật được cursor"
                        )

                        return@withContext SyncResult.FAILURE
                    }

                    cursor = newCursor

                    Log.d(
                        TAG,
                        "Đã xử lý trang PULL. " +
                                "Cursor mới: $cursor, " +
                                "số bản ghi: ${page.items.size}"
                    )

                    if (!page.hasMore) break
                }

                Log.d(TAG, "Hoàn thành PULL")

                SyncResult.SUCCESS

            } catch (e: IOException) {

                Log.e(
                    TAG,
                    "Lỗi kết nối khi PULL: ${e.message}",
                    e
                )

                SyncResult.RETRY

            } catch (e: CancellationException) {

                throw e

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Lỗi xử lý khi PULL: ${e.message}",
                    e
                )

                SyncResult.FAILURE
            }
        }

    suspend fun synchronize(): SyncResult {

        Log.d(TAG, "Bắt đầu quá trình đồng bộ")

        movementDao.resetStuckSyncing()

        var pushResult = SyncResult.SUCCESS
        var round = 0
        do {
            round++
            Log.d(TAG, "Bắt đầu PUSH vòng $round")
            pushResult = pushChanges()
            Log.d(TAG, "Kết quả PUSH vòng $round: $pushResult")

            if (pushResult != SyncResult.SUCCESS) {
                Log.w(TAG, "Dừng đồng bộ vì PUSH chưa hoàn thành")
                return pushResult
            }

            val pending = movementDao.getOutboxBatch()
            if (pending.isEmpty()) break
        } while (round < 3)

        if (movementDao.getOutboxBatch().isNotEmpty()) {
            Log.w(TAG, "Vẫn còn outbox sau 3 vòng PUSH; yêu cầu WorkManager thử lại")
            return SyncResult.RETRY
        }

        Log.d(TAG, "Bắt đầu PULL")
        val pullResult = pullChanges()
        Log.d(TAG, "Kết quả PULL: $pullResult")
        return pullResult
    }

    private suspend fun resolveConflict(
        local: MovementEntity,
        server: RemoteMovementDto
    ) {

        val decision = ConflictResolver.resolve(
            local,
            server
        )

        database.withTransaction {
            syncDao.insertConflict(
                ConflictEntity(
                    movementId = local.id,
                    localData = gson.toJson(local),
                    serverData = gson.toJson(server),
                    localRev = local.localRev,
                    serverVersion = server.serverVersion,
                    resolution = decision.action.name,
                    createdAt = Clock.nowUtc()
                )
            )

            when (decision.action) {

                ConflictAction.KEEP_SERVER -> {

                    if (server.isDeleted) {

                        movementDao.deleteConflictWinner(local.id)

                    } else {

                        movementDao.replaceWithServer(
                            id = server.id,
                            productId = server.productId,
                            productName = server.productName,
                            type = server.type,
                            quantity = server.quantity,
                            batchCode = server.batchCode,
                            partner = server.partner,
                            note = server.note,
                            status = server.status,
                            serverVersion = server.serverVersion,
                            isDeleted = server.isDeleted,
                            createdAt = server.createdAt,
                            updatedAt = server.updatedAt
                        )
                    }

                    Log.d(
                        TAG,
                        "Giữ dữ liệu server cho phiếu ${local.id}"
                    )
                }

                ConflictAction.RETRY_LOCAL,
                ConflictAction.RETRY_LOCAL_DELETE -> {

                    movementDao.updateVersionForRetry(
                        id = local.id,
                        expectedLocalRev = local.localRev,
                        expectedMutationId = local.mutationId,
                        newServerVersion = decision.latestServerVersion
                    )

                    Log.d(
                        TAG,
                        "Giữ dữ liệu local và gửi lại phiếu ${local.id}"
                    )
                }
            }
        }
    }

    private suspend fun saveConflictAndMark(
        local: MovementEntity,
        message: String
    ) {
        database.withTransaction {
            saveConflict(local, message, server = null)
            movementDao.markConflict(
                local.id,
                local.localRev,
                local.mutationId
            )
        }
    }

    private fun isRetryableHttpCode(code: Int): Boolean {
        return code == 408 ||
                code == 429 ||
                code in 500..599
    }
}