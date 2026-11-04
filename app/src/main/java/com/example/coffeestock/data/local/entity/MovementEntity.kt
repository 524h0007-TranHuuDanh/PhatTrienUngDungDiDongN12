package com.example.coffeestock.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stock_movement",
    indices = [
        Index(value = ["syncState"]),
        Index(value = ["pendingOp"]),
        Index(value = ["productId"])
    ]
)
data class MovementEntity(
    @PrimaryKey
    val id: String,

    val productId: String,

    val productName: String,

    val type: String,

    val quantity: Double,

    val batchCode: String = "",

    val partner: String = "",

    val note: String = "",

    val status: String = "DRAFT",

    val serverVersion: Long = 0,

    val syncState: String = "PENDING",

    val pendingOp: String = "CREATE",

    val localRev: Long = 1,

    val mutationId: String,

    val isDeleted: Boolean = false,

    val createdAt: String,

    val updatedAt: String
)