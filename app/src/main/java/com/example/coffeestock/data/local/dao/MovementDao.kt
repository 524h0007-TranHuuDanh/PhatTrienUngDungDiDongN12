package com.example.coffeestock.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.coffeestock.data.local.entity.MovementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MovementDao {

    @Query("""
        SELECT * FROM stock_movement
        WHERE isDeleted = 0
        ORDER BY createdAt DESC
    """)
    fun getAllMovements(): Flow<List<MovementEntity>>

    @Query("""
        SELECT * FROM stock_movement
        WHERE id = :movementId
        LIMIT 1
    """)
    suspend fun getMovementById(movementId: String): MovementEntity?

    @Query("""
        SELECT * FROM stock_movement
        WHERE syncState IN ('PENDING', 'FAILED', 'CONFLICT')
        ORDER BY createdAt ASC
    """)
    suspend fun getPendingMovements(): List<MovementEntity>

    @Query("""
        SELECT * FROM stock_movement
        WHERE syncState = 'PENDING'
        ORDER BY createdAt ASC
    """)
    suspend fun getWaitingMovements(): List<MovementEntity>

    @Query("""
        SELECT * FROM stock_movement
        WHERE productId = :productId
        AND status = 'CONFIRMED'
        AND isDeleted = 0
    """)
    fun getConfirmedMovementsByProduct(
        productId: String
    ): Flow<List<MovementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovement(movement: MovementEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovements(movements: List<MovementEntity>)

    @Query("""
        UPDATE stock_movement
        SET syncState = :syncState
        WHERE id = :movementId
    """)
    suspend fun updateSyncState(
        movementId: String,
        syncState: String
    )

    @Query("DELETE FROM stock_movement WHERE id = :movementId")
    suspend fun deleteMovementPermanently(movementId: String)

    @Query("""
        SELECT COUNT(*) FROM stock_movement
        WHERE pendingOp != 'NONE'
    """)
    fun countPendingChanges(): Flow<Int>

    @Query("""
        SELECT * FROM stock_movement
        WHERE productId = :productId
        AND status = 'CONFIRMED'
        AND isDeleted = 0
    """)
    suspend fun getConfirmedMovementsByProductOnce(
        productId: String
    ): List<MovementEntity>

    @Query("""
        UPDATE stock_movement
        SET syncState = 'PENDING'
        WHERE syncState = 'SYNCING'
        AND pendingOp != 'NONE'
    """)
    suspend fun resetStuckSyncing(): Int

    @Query("""
        UPDATE stock_movement
        SET syncState = 'PENDING'
        WHERE syncState = 'FAILED'
        AND pendingOp != 'NONE'
    """)
    suspend fun requeueFailed(): Int

    @Query("""
        UPDATE stock_movement
        SET syncState = 'PENDING'
        WHERE id = :movementId
        AND localRev = :expectedLocalRev
        AND mutationId = :expectedMutationId
    """)
    suspend fun requeueOne(
        movementId: String,
        expectedLocalRev: Long,
        expectedMutationId: String
    ): Int

    @Query("""
        SELECT * FROM stock_movement
        WHERE pendingOp != 'NONE'
        AND syncState = 'PENDING'
        ORDER BY createdAt ASC
    """)
    suspend fun getOutboxBatch(): List<MovementEntity>

    @Query("""
        UPDATE stock_movement
        SET syncState = 'SYNCING'
        WHERE id = :movementId
        AND localRev = :expectedLocalRev
        AND mutationId = :expectedMutationId
        AND pendingOp != 'NONE'
    """)
    suspend fun markSyncing(
        movementId: String,
        expectedLocalRev: Long,
        expectedMutationId: String
    ): Int

    @Query("""
        UPDATE stock_movement
        SET syncState = 'SYNCED',
            pendingOp = 'NONE',
            serverVersion = :newServerVersion
        WHERE id = :movementId
        AND localRev = :expectedLocalRev
        AND mutationId = :expectedMutationId
    """)
    suspend fun markSynced(
        movementId: String,
        expectedLocalRev: Long,
        expectedMutationId: String,
        newServerVersion: Long
    ): Int

    @Query("""
        UPDATE stock_movement
        SET serverVersion = :newServerVersion,
            pendingOp = CASE
                WHEN pendingOp = 'CREATE' THEN 'UPDATE'
                ELSE pendingOp
            END,
            syncState = 'PENDING'
        WHERE id = :movementId
        AND localRev != :expectedLocalRev
        AND pendingOp != 'NONE'
    """)
    suspend fun adoptServerVersionAfterConcurrentEdit(
        movementId: String,
        expectedLocalRev: Long,
        newServerVersion: Long
    ): Int

    @Query("""
        UPDATE stock_movement
        SET syncState = 'FAILED'
        WHERE id = :movementId
        AND localRev = :expectedLocalRev
        AND mutationId = :expectedMutationId
    """)
    suspend fun markSyncFailed(
        movementId: String,
        expectedLocalRev: Long,
        expectedMutationId: String
    ): Int

    @Query("""
        UPDATE stock_movement
        SET syncState = 'CONFLICT'
        WHERE id = :movementId
        AND localRev = :expectedLocalRev
        AND mutationId = :expectedMutationId
    """)
    suspend fun markConflict(
        movementId: String,
        expectedLocalRev: Long,
        expectedMutationId: String
    ): Int

    @Query("""
        UPDATE stock_movement
        SET syncState = 'PENDING'
        WHERE id = :movementId
        AND localRev = :expectedLocalRev
        AND mutationId = :expectedMutationId
    """)
    suspend fun markSyncPending(
        movementId: String,
        expectedLocalRev: Long,
        expectedMutationId: String
    ): Int

    @Query("""
        UPDATE stock_movement
        SET productId = :productId,
            productName = :productName,
            type = :type,
            quantity = :quantity,
            batchCode = :batchCode,
            partner = :partner,
            note = :note,
            status = :status,
            serverVersion = :serverVersion,
            isDeleted = :isDeleted,
            createdAt = :createdAt,
            updatedAt = :updatedAt,
            syncState = 'SYNCED',
            pendingOp = 'NONE'
        WHERE id = :id
        AND pendingOp = 'NONE'
        AND serverVersion < :serverVersion
    """)
    suspend fun updateFromServer(
        id: String,
        productId: String,
        productName: String,
        type: String,
        quantity: Double,
        batchCode: String,
        partner: String,
        note: String,
        status: String,
        serverVersion: Long,
        isDeleted: Boolean,
        createdAt: String,
        updatedAt: String
    ): Int

    @Query("""
        DELETE FROM stock_movement
        WHERE id = :id
        AND pendingOp = 'NONE'
        AND serverVersion < :serverVersion
    """)
    suspend fun deleteFromServer(
        id: String,
        serverVersion: Long
    ): Int

    @Query("""
        UPDATE stock_movement
        SET serverVersion = :newServerVersion,
            syncState = 'PENDING'
        WHERE id = :id
        AND localRev = :expectedLocalRev
        AND mutationId = :expectedMutationId
        AND pendingOp != 'NONE'
    """)
    suspend fun updateVersionForRetry(
        id: String,
        expectedLocalRev: Long,
        expectedMutationId: String,
        newServerVersion: Long
    ): Int

    @Query("""
        UPDATE stock_movement
        SET productId = :productId,
            productName = :productName,
            type = :type,
            quantity = :quantity,
            batchCode = :batchCode,
            partner = :partner,
            note = :note,
            status = :status,
            serverVersion = :serverVersion,
            syncState = 'SYNCED',
            pendingOp = 'NONE',
            isDeleted = :isDeleted,
            createdAt = :createdAt,
            updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun replaceWithServer(
        id: String,
        productId: String,
        productName: String,
        type: String,
        quantity: Double,
        batchCode: String,
        partner: String,
        note: String,
        status: String,
        serverVersion: Long,
        isDeleted: Boolean,
        createdAt: String,
        updatedAt: String
    ): Int

    @Query("""
        DELETE FROM stock_movement
        WHERE id = :id
    """)
    suspend fun deleteConflictWinner(id: String): Int

    @Query("""
        SELECT COUNT(*) FROM stock_movement
        WHERE pendingOp != 'NONE'
        AND syncState IN ('PENDING', 'SYNCING')
    """)
    fun observePendingCount(): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM stock_movement
        WHERE pendingOp != 'NONE'
        AND syncState = 'FAILED'
    """)
    fun observeFailedCount(): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM stock_movement
        WHERE syncState = 'CONFLICT'
    """)
    fun observeConflictCount(): Flow<Int>
}