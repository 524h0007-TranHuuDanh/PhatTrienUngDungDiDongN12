package com.example.coffeestock.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coffeestock.data.local.AppDatabase
import com.example.coffeestock.data.local.entity.MovementEntity
import com.example.coffeestock.sync.testutil.FakeApiService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class MultiDeviceConflictTest {

    private lateinit var dbA: AppDatabase
    private lateinit var dbB: AppDatabase
    private lateinit var server: FakeApiService

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        dbA = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dbB = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        server = FakeApiService()
    }

    @After fun tearDown() {
        dbA.close()
        dbB.close()
    }

    private fun engine(db: AppDatabase, api: FakeApiService) = SyncEngine(
        database = db,
        movementDao = db.movementDao(),
        syncDao = db.syncDao(),
        apiService = api,
        apiKey = "test-key",
        pullDataApplier = PullDataApplier(db)
    )

    private fun movement(
        id: String = "m1",
        mutationId: String,
        quantity: Double,
        serverVersion: Long = 0L,
        pendingOp: String = "CREATE",
        syncState: String = "PENDING",
        localRev: Long = 1L,
        isDeleted: Boolean = false
    ) = MovementEntity(
        id = id, productId = "product-1", productName = "Arabica",
        type = "IN", quantity = quantity, batchCode = "BATCH-01",
        partner = "Supplier", note = "test", status = "DRAFT",
        serverVersion = serverVersion, syncState = syncState,
        pendingOp = pendingOp, localRev = localRev,
        mutationId = mutationId, isDeleted = isDeleted,
        createdAt = "2026-11-03T00:00:00Z",
        updatedAt = "2026-11-03T00:00:00Z"
    )

    @Test fun tc07_twoDevices_conflictAndResolution() = runBlocking {
        dbA.movementDao().insertMovement(
            movement(mutationId = "A-create", quantity = 10.0)
        )
        assertEquals(SyncResult.SUCCESS, engine(dbA, server).synchronize())
        assertEquals(1L, dbA.movementDao().getMovementById("m1")!!.serverVersion)

        val aAfterCreate = dbA.movementDao().getMovementById("m1")!!
        dbA.movementDao().insertMovement(
            aAfterCreate.copy(
                quantity = 15.0,
                pendingOp = "UPDATE",
                syncState = "PENDING",
                localRev = 2L,
                mutationId = "A-update"
            )
        )
        assertEquals(SyncResult.SUCCESS, engine(dbA, server).synchronize())
        assertEquals(2L, dbA.movementDao().getMovementById("m1")!!.serverVersion)

        val serverAfterA = server.getRecord("m1")!!
        assertEquals(2L, serverAfterA.serverVersion)
        assertEquals(15.0, serverAfterA.quantity, 0.0)

        dbB.movementDao().insertMovement(
            movement(
                mutationId = "B-pull", quantity = 10.0,
                serverVersion = 1L, pendingOp = "NONE", syncState = "SYNCED",
                localRev = 0L
            )
        )
        dbB.movementDao().insertMovement(
            dbB.movementDao().getMovementById("m1")!!.copy(
                quantity = 25.0,
                pendingOp = "UPDATE",
                syncState = "PENDING",
                localRev = 1L,
                mutationId = "B-update"
            )
        )

        val resultB = engine(dbB, server).synchronize()
        assertEquals(SyncResult.SUCCESS, resultB)

        val bFinal = dbB.movementDao().getMovementById("m1")!!
        assertEquals("SYNCED", bFinal.syncState)
        assertEquals("NONE", bFinal.pendingOp)
        assertEquals(3L, bFinal.serverVersion)
        assertEquals(25.0, bFinal.quantity, 0.0)

        val serverFinal = server.getRecord("m1")!!
        assertEquals(3L, serverFinal.serverVersion)
        assertEquals(25.0, serverFinal.quantity, 0.0)

        val conflicts = dbB.syncDao().getAllConflicts().first()
        assertEquals(1, conflicts.size)
        assertEquals("RETRY_LOCAL", conflicts[0].resolution)

        println("TC07: PASS")
        println("  A update: qty=15 → server v2")
        println("  B stale (base=1) push → 409 VERSION_CONFLICT")
        println("  ConflictResolver → RETRY_LOCAL")
        println("  B retry với base=2 → server v3, qty=25")
        println("  Conflict log: ${conflicts.size} entry, resolution=${conflicts[0].resolution}")
    }
}