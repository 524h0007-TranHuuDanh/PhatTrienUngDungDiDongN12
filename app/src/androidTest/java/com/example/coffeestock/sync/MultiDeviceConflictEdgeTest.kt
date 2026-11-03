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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiDeviceConflictEdgeTest {

    private lateinit var dbA: AppDatabase
    private lateinit var dbB: AppDatabase
    private lateinit var server: FakeApiService

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        dbA = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dbB = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        server = FakeApiService()
    }

    @After
    fun tearDown() {
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
        isDeleted: Boolean = false,
        status: String = "DRAFT"
    ) = MovementEntity(
        id = id, productId = "product-1", productName = "Arabica",
        type = "IN", quantity = quantity, batchCode = "BATCH-01",
        partner = "Supplier", note = "test", status = status,
        serverVersion = serverVersion, syncState = syncState,
        pendingOp = pendingOp, localRev = localRev,
        mutationId = mutationId, isDeleted = isDeleted,
        createdAt = "2026-11-03T00:00:00Z",
        updatedAt = "2026-11-03T00:00:00Z"
    )

    @Test
    fun tc10_twoDevicesCreateSameId_BMarkedFailed() = runBlocking {
        dbA.movementDao().insertMovement(
            movement(mutationId = "A-create", quantity = 10.0)
        )
        assertEquals(SyncResult.SUCCESS, engine(dbA, server).synchronize())

        dbB.movementDao().insertMovement(
            movement(mutationId = "B-create", quantity = 20.0)
        )

        assertEquals(SyncResult.SUCCESS, engine(dbB, server).synchronize())

        val serverFinal = server.getRecord("m1")!!
        assertEquals(1L, serverFinal.serverVersion)
        assertEquals(10.0, serverFinal.quantity, 0.0)

        val bFinal = dbB.movementDao().getMovementById("m1")!!
        assertEquals("FAILED", bFinal.syncState)
        assertEquals("CREATE", bFinal.pendingOp)

        println("TC10: PASS — CREATE trùng ID → B FAILED, server giữ A's data")
    }

    @Test
    fun tc11_deleteVsEdit_serverDeleteWins() = runBlocking {
        dbA.movementDao().insertMovement(
            movement(mutationId = "seed", quantity = 5.0)
        )
        assertEquals(SyncResult.SUCCESS, engine(dbA, server).synchronize())

        dbB.movementDao().insertMovement(
            movement(
                mutationId = "pull", quantity = 5.0,
                serverVersion = 1L, pendingOp = "NONE", syncState = "SYNCED",
                localRev = 0L
            )
        )

        dbA.movementDao().insertMovement(
            dbA.movementDao().getMovementById("m1")!!.copy(
                isDeleted = true,
                pendingOp = "DELETE",
                syncState = "PENDING",
                localRev = 2L,
                mutationId = "A-del"
            )
        )
        assertEquals(SyncResult.SUCCESS, engine(dbA, server).synchronize())

        val aFinal = dbA.movementDao().getMovementById("m1")
        assertNotNull("A's local phải tồn tại dưới dạng tombstone", aFinal)
        assertEquals("SYNCED", aFinal!!.syncState)
        assertEquals("NONE", aFinal.pendingOp)
        assertTrue("A's local phải bị đánh dấu isDeleted", aFinal.isDeleted)

        dbB.movementDao().insertMovement(
            dbB.movementDao().getMovementById("m1")!!.copy(
                quantity = 25.0,
                pendingOp = "UPDATE",
                syncState = "PENDING",
                localRev = 2L,
                mutationId = "B-upd"
            )
        )

        assertEquals(SyncResult.SUCCESS, engine(dbB, server).synchronize())

        assertNull("B's local phải bị xóa cứng", dbB.movementDao().getMovementById("m1"))

        val conflicts = dbB.syncDao().getAllConflicts().first()
        assertEquals(1, conflicts.size)
        assertEquals("KEEP_SERVER", conflicts[0].resolution)

        println("TC11: PASS — A xóa thắng, B edit bị hủy, conflict KEEP_SERVER")
    }

    @Test
    fun tc12_editVsDelete_localDeleteRetriesAndWins() = runBlocking {
        dbA.movementDao().insertMovement(
            movement(mutationId = "seed", quantity = 5.0)
        )
        assertEquals(SyncResult.SUCCESS, engine(dbA, server).synchronize())

        dbB.movementDao().insertMovement(
            movement(
                mutationId = "pull", quantity = 5.0,
                serverVersion = 1L, pendingOp = "NONE", syncState = "SYNCED",
                localRev = 0L
            )
        )

        dbA.movementDao().insertMovement(
            dbA.movementDao().getMovementById("m1")!!.copy(
                quantity = 20.0,
                pendingOp = "UPDATE",
                syncState = "PENDING",
                localRev = 2L,
                mutationId = "A-upd"
            )
        )
        assertEquals(SyncResult.SUCCESS, engine(dbA, server).synchronize())
        assertEquals(2L, server.getRecord("m1")!!.serverVersion)

        dbB.movementDao().insertMovement(
            dbB.movementDao().getMovementById("m1")!!.copy(
                isDeleted = true,
                pendingOp = "DELETE",
                syncState = "PENDING",
                localRev = 2L,
                mutationId = "B-del"
            )
        )

        assertEquals(SyncResult.SUCCESS, engine(dbB, server).synchronize())

        val bFinal = dbB.movementDao().getMovementById("m1")
        assertNotNull("B's local phải tồn tại dưới dạng tombstone", bFinal)
        assertEquals("SYNCED", bFinal!!.syncState)
        assertTrue(bFinal.isDeleted)
        assertEquals(3L, bFinal.serverVersion)

        val conflictsB = dbB.syncDao().getAllConflicts().first()
        assertEquals("RETRY_LOCAL_DELETE", conflictsB[0].resolution)

        assertEquals(SyncResult.SUCCESS, engine(dbA, server).pullChanges())
        assertNull("A's local phải bị xóa cứng sau khi pull v3", dbA.movementDao().getMovementById("m1"))

        println("TC12: PASS — A sửa v2, B xóa retry v3, A pull → hard delete")
    }

    @Test
    fun tc13_bothDeleteConcurrently_convergeToDeleted() = runBlocking {
        dbA.movementDao().insertMovement(
            movement(mutationId = "seed", quantity = 5.0)
        )
        assertEquals(SyncResult.SUCCESS, engine(dbA, server).synchronize())

        dbB.movementDao().insertMovement(
            movement(
                mutationId = "pull", quantity = 5.0,
                serverVersion = 1L, pendingOp = "NONE", syncState = "SYNCED",
                localRev = 0L
            )
        )

        dbA.movementDao().insertMovement(
            dbA.movementDao().getMovementById("m1")!!.copy(
                isDeleted = true, pendingOp = "DELETE",
                syncState = "PENDING", localRev = 2L, mutationId = "A-del"
            )
        )
        dbB.movementDao().insertMovement(
            dbB.movementDao().getMovementById("m1")!!.copy(
                isDeleted = true, pendingOp = "DELETE",
                syncState = "PENDING", localRev = 2L, mutationId = "B-del"
            )
        )

        assertEquals(SyncResult.SUCCESS, engine(dbA, server).synchronize())

        assertEquals(SyncResult.SUCCESS, engine(dbB, server).synchronize())

        assertNull("B's local phải bị xóa cứng", dbB.movementDao().getMovementById("m1"))

        val conflictsB = dbB.syncDao().getAllConflicts().first()
        assertEquals("KEEP_SERVER", conflictsB[0].resolution)

        println("TC13: PASS — cùng xóa, B KEEP_SERVER, idempotent server v2")
    }
}