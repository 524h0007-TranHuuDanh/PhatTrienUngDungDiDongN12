package com.example.coffeestock.sync

import com.example.coffeestock.data.local.entity.MovementEntity
import com.example.coffeestock.data.remote.dto.RemoteMovementDto

enum class ConflictAction {
    KEEP_SERVER,
    RETRY_LOCAL,
    RETRY_LOCAL_DELETE
}

data class ConflictDecision(
    val action: ConflictAction,
    val latestServerVersion: Long,
    val message: String
)

object ConflictResolver {

    fun resolve(
        local: MovementEntity,
        server: RemoteMovementDto
    ): ConflictDecision {

        if (server.isDeleted) {
            return ConflictDecision(
                action = ConflictAction.KEEP_SERVER,
                latestServerVersion = server.serverVersion,
                message = "Máy chủ đã xóa phiếu. Giữ trạng thái đã xóa."
            )
        }

        if (server.status == "CONFIRMED") {
            return ConflictDecision(
                action = ConflictAction.KEEP_SERVER,
                latestServerVersion = server.serverVersion,
                message = "Phiếu trên máy chủ đã xác nhận."
            )
        }

        if (local.isDeleted || local.pendingOp == "DELETE") {
            return ConflictDecision(
                action = ConflictAction.RETRY_LOCAL_DELETE,
                latestServerVersion = server.serverVersion,
                message = "Giữ yêu cầu xóa ở local và gửi lại."
            )
        }

        if (local.status == "CONFIRMED") {
            return ConflictDecision(
                action = ConflictAction.RETRY_LOCAL,
                latestServerVersion = server.serverVersion,
                message = "Giữ phiếu đã xác nhận ở local và gửi lại."
            )
        }

        return ConflictDecision(
            action = ConflictAction.RETRY_LOCAL,
            latestServerVersion = server.serverVersion,
            message = "Giữ thay đổi local và gửi lại với serverVersion mới."
        )
    }
}