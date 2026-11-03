package com.example.coffeestock.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coffeestock.data.local.AppDatabase
import com.example.coffeestock.data.local.entity.MovementEntity
import com.example.coffeestock.data.remote.dto.RemoteMovementDto
import com.example.coffeestock.sync.testutil.FakeApiService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncConsistencyTest {

    private lateinit var db: AppDatabase
    private lateinit var server: FakeApiService

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        server = FakeApiService()
    }

    @After
    fun tearDown() { db.close() }

    private fun engine() = SyncEngine(
        database = db,
        movementDao = db.movementDao(),
        syncDao = db.syncDao(),
        apiService = server,
        apiKey = "test-key",
        pullDataApplier = PullDataApplier(db)
    )

    private fun pending(
        id: String, mutationId: String,
        quantity: Double, type: String = "IN",
        batchCode: String = "BATCH-01"
    ) = MovementEntity(
        id = id, productId = "product-1", productName = "Arabica",
        type = type, quantity = quantity, batchCode = batchCode,
        partner = "Supplier", note = "consistency", status = "DRAFT",
        serverVersion = 0L, syncState = "PENDING", pendingOp = "CREATE",
        localRev = 1L, mutationId = mutationId, isDeleted = false,
        createdAt = "2026-11-03T00:00:00Z", updatedAt = "2026-11-03T00:00:00Z"
    )

    private fun assertMatches(server: RemoteMovementDto?, local: MovementEntity?) {
        assertNotNull("Server missing record ${local?.id}", server)
        assertNotNull("Local missing record ${server?.id}", local)
        server!!; local!!

        assertEquals("id", server.id, local.id)
        assertEquals("productId", server.productId, local.productId)
        assertEquals("productName", server.productName, local.productName)
        assertEquals("type", server.type, local.type)
        assertEquals("quantity", server.quantity, local.quantity, 0.0)
        assertEquals("batchCode", server.batchCode, local.batchCode)
        assertEquals("partner", server.partner, local.partner)
        assertEquals("note", server.note, local.note)
        assertEquals("status", server.status, local.status)
        assertEquals("serverVersion", server.serverVersion, local.serverVersion)
        assertEquals("isDeleted", server.isDeleted, local.isDeleted)
        assertEquals("syncState", "SYNCED", local.syncState)
        assertEquals("pendingOp", "NONE", local.pendingOp)
    }

    @Test
    fun afterFullSync_roomAndServerMatchOnAllRecords() = runBlocking {
        val n = 10
        val list = (1..n).map { i ->
            pending("m$i", "mut-$i", i.toDouble())
        }
        db.movementDao().insertMovements(list)

        assertEquals(SyncResult.SUCCESS, engine().synchronize())
        assertEquals(n, server.getRecordCount())

        val localList = db.movementDao().getAllMovements().first()
        assertEquals(n, localList.size)

        for (local in localList) {
            assertMatches(server.getRecord(local.id), local)
        }

        val cursor = PullDataApplier(db).getCurrentCursor()
        val maxSeq = server.records.values.maxOf { it.serverSeq }
        assertEquals(maxSeq, cursor)

        println("Consistency: PASS — $n/$n records khớp giữa Room và server")
    }

    @Test
    fun incrementalPull_onlyFetchesNewRecords() = runBlocking {
        db.movementDao().insertMovements(
            (1..3).map { pending("m$it", "mut-$it", it.toDouble()) }
        )
        assertEquals(SyncResult.SUCCESS, engine().synchronize())
        assertEquals(3, db.movementDao().getAllMovements().first().size)

        server.records["m4"] = RemoteMovementDto(
            id = "m4", productId = "product-1", productName = "Arabica",
            type = "IN", quantity = 4.0, batchCode = "BATCH-01",
            partner = "Device-B", note = "from B", status = "DRAFT",
            serverVersion = 1L, serverSeq = 100L, isDeleted = false,
            createdAt = "2026-11-03T01:00:00Z", updatedAt = "2026-11-03T01:00:00Z"
        )
        server.records["m5"] = server.records["m4"]!!.copy(
            id = "m5", quantity = 5.0, serverSeq = 101L
        )

        val pullResult = engine().pullChanges()
        assertEquals(SyncResult.SUCCESS, pullResult)

        val all = db.movementDao().getAllMovements().first()
        assertEquals(5, all.size)
        assertNotNull(all.find { it.id == "m4" })
        assertNotNull(all.find { it.id == "m5" })

        for (local in all) {
            assertMatches(server.getRecord(local.id), local)
        }

        println("Incremental pull: PASS — pull thêm 2 records từ cursor cũ")
    }


    @Test
    fun afterServerTombstone_localDeleted() = runBlocking {
        db.movementDao().insertMovement(pending("m1", "mut-1", 5.0))
        assertEquals(SyncResult.SUCCESS, engine().synchronize())
        assertEquals(1, db.movementDao().getAllMovements().first().size)

        server.records["m1"] = server.records["m1"]!!.copy(
            serverVersion = 2L, serverSeq = 100L, isDeleted = true
        )

        assertEquals(SyncResult.SUCCESS, engine().pullChanges())

        val all = db.movementDao().getAllMovements().first()
        assertTrue(all.isEmpty())

        println("Tombstone: PASS — pull tombstone → local hard delete")
    }

    @Test
    fun afterServerUpdate_roomUpdated() = runBlocking {
        db.movementDao().insertMovement(pending("m1", "mut-1", 5.0))
        assertEquals(SyncResult.SUCCESS, engine().synchronize())

        server.records["m1"] = server.records["m1"]!!.copy(
            quantity = 99.0, serverVersion = 2L, serverSeq = 100L
        )

        assertEquals(SyncResult.SUCCESS, engine().pullChanges())

        val local = db.movementDao().getMovementById("m1")!!
        assertEquals(99.0, local.quantity, 0.0)
        assertEquals(2L, local.serverVersion)

        assertMatches(server.getRecord("m1"), local)
        println("Update pull: PASS — server v2 → local quantity=99")
    }
}