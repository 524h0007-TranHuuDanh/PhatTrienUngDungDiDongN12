package com.example.coffeestock.sync.testutil

import com.example.coffeestock.data.remote.ApiService
import com.example.coffeestock.data.remote.dto.PullResponseDto
import com.example.coffeestock.data.remote.dto.PushMovementDto
import com.example.coffeestock.data.remote.dto.PushRequestDto
import com.example.coffeestock.data.remote.dto.PushResponseDto
import com.example.coffeestock.data.remote.dto.PushResultDto
import com.example.coffeestock.data.remote.dto.RemoteMovementDto
import com.example.coffeestock.data.remote.dto.SyncErrorDto
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class FakeApiService : ApiService {

    enum class FailMode { NONE, TIMEOUT, IO_ERROR }

    val records = mutableMapOf<String, RemoteMovementDto>()
    val mutationCache = mutableMapOf<String, PushResultDto>()
    private var nextSeq = 1L

    var failPushMode: FailMode = FailMode.NONE
    var failPullMode: FailMode = FailMode.NONE

    val pushCount = AtomicInteger(0)
    val pullCount = AtomicInteger(0)

    fun getRecord(id: String): RemoteMovementDto? = records[id]
    fun getRecordCount(): Int = records.size
    fun resetCounters() { pushCount.set(0); pullCount.set(0) }

    override suspend fun pushChanges(
        apiKey: String,
        request: PushRequestDto
    ): Response<PushResponseDto> {
        pushCount.incrementAndGet()
        when (failPushMode) {
            FailMode.TIMEOUT -> throw SocketTimeoutException("push timeout")
            FailMode.IO_ERROR -> throw IOException("push io")
            FailMode.NONE -> { }
        }
        val results = request.items.map { processPush(it) }
        return Response.success(PushResponseDto(results = results))
    }

    override suspend fun pullChanges(
        apiKey: String,
        afterSeq: Long,
        limit: Int
    ): Response<PullResponseDto> {
        pullCount.incrementAndGet()
        when (failPullMode) {
            FailMode.TIMEOUT -> throw SocketTimeoutException("pull timeout")
            FailMode.IO_ERROR -> throw IOException("pull io")
            FailMode.NONE -> { }
        }
        val changes = records.values
            .filter { it.serverSeq > afterSeq }
            .sortedBy { it.serverSeq }
        val hasMore = changes.size > limit
        val items = changes.take(limit)
        val nextCursor = items.lastOrNull()?.serverSeq ?: afterSeq
        return Response.success(
            PullResponseDto(items = items, nextCursor = nextCursor, hasMore = hasMore)
        )
    }

    private fun processPush(item: PushMovementDto): PushResultDto {
        mutationCache[item.mutationId]?.let { return it }

        val existing = records[item.id]

        if (item.operation == "CREATE" && existing != null) {
            return PushResultDto(
                id = item.id,
                mutationId = item.mutationId,
                ok = false,
                movement = null,
                error = SyncErrorDto(
                    code = "MOVEMENT_ALREADY_EXISTS",
                    message = "Phiếu đã tồn tại",
                    currentMovement = existing
                )
            )
        }

        if (item.operation != "CREATE" && existing == null) {
            return PushResultDto(
                id = item.id,
                mutationId = item.mutationId,
                ok = false,
                movement = null,
                error = SyncErrorDto(
                    code = "MOVEMENT_NOT_FOUND",
                    message = "Không tìm thấy phiếu",
                    currentMovement = null
                )
            )
        }

        if (item.operation != "CREATE" &&
            existing != null &&
            existing.serverVersion != item.baseVersion
        ) {
            return PushResultDto(
                id = item.id,
                mutationId = item.mutationId,
                ok = false,
                movement = null,
                error = SyncErrorDto(
                    code = "VERSION_CONFLICT",
                    message = "Phiên bản đã thay đổi",
                    currentMovement = existing
                )
            )
        }

        val newVersion = (existing?.serverVersion ?: 0L) + 1
        val isDelete = item.operation == "DELETE" || item.isDeleted
        val newRecord = RemoteMovementDto(
            id = item.id,
            productId = item.productId,
            productName = item.productName,
            type = item.type,
            quantity = item.quantity,
            batchCode = item.batchCode,
            partner = item.partner,
            note = item.note,
            status = item.status,
            serverVersion = newVersion,
            serverSeq = nextSeq++,
            isDeleted = isDelete,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt
        )
        records[item.id] = newRecord

        val result = PushResultDto(
            id = item.id,
            mutationId = item.mutationId,
            ok = true,
            movement = newRecord,
            error = null
        )
        mutationCache[item.mutationId] = result
        return result
    }
}