package com.example.coffeestock.sync

import androidx.room.withTransaction
import com.example.coffeestock.data.local.AppDatabase
import com.example.coffeestock.data.local.entity.MovementEntity
import com.example.coffeestock.data.local.entity.SyncMetaEntity
import com.example.coffeestock.data.remote.dto.PullResponseDto
import com.example.coffeestock.data.remote.dto.RemoteMovementDto
import java.util.UUID

class PullDataApplier(
    private val database: AppDatabase
) {

    companion object {
        private const val CURSOR_KEY = "movements_last_seq"
    }

    suspend fun getCurrentCursor(): Long {

        val value = database.syncDao()
            .getSyncMeta(CURSOR_KEY)

        return value?.toLongOrNull() ?: 0L
    }

    suspend fun applyPage(
        response: PullResponseDto
    ) {

        database.withTransaction {

            val movementDao = database.movementDao()
            val syncDao = database.syncDao()

            val oldCursor = syncDao
                .getSyncMeta(CURSOR_KEY)
                ?.toLongOrNull() ?: 0L

            val newCursor = response.nextCursor ?: oldCursor

            require(newCursor >= oldCursor) {
                "Cursor máy chủ không được nhỏ hơn cursor hiện tại"
            }

            for (remote in response.items) {

                val local = movementDao.getMovementById(
                    remote.id
                )

                if (local != null && local.pendingOp != "NONE") {
                    continue
                }

                if (remote.isDeleted) {

                    if (local != null) {

                        movementDao.deleteFromServer(
                            id = remote.id,
                            serverVersion = remote.serverVersion
                        )
                    }

                    continue
                }

                if (local == null) {

                    movementDao.insertMovement(
                        remote.toLocalEntity()
                    )

                } else {

                    movementDao.updateFromServer(
                        id = remote.id,
                        productId = remote.productId,
                        productName = remote.productName,
                        type = remote.type,
                        quantity = remote.quantity,
                        batchCode = remote.batchCode,
                        partner = remote.partner,
                        note = remote.note,
                        status = remote.status,
                        serverVersion = remote.serverVersion,
                        isDeleted = remote.isDeleted,
                        createdAt = remote.createdAt,
                        updatedAt = remote.updatedAt
                    )
                }
            }

            syncDao.saveSyncMeta(
                SyncMetaEntity(
                    key = CURSOR_KEY,
                    value = newCursor.toString()
                )
            )
        }
    }

    private fun RemoteMovementDto.toLocalEntity(): MovementEntity {

        return MovementEntity(
            id = id,
            productId = productId,
            productName = productName,
            type = type,
            quantity = quantity,
            batchCode = batchCode,
            partner = partner,
            note = note,
            status = status,
            serverVersion = serverVersion,
            syncState = "SYNCED",
            pendingOp = "NONE",
            localRev = 0,
            mutationId = UUID.randomUUID().toString(),
            isDeleted = false,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}