package com.example.coffeestock.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coffeestock.data.local.AppDatabase
import com.example.coffeestock.data.local.entity.MovementEntity
import com.example.coffeestock.data.remote.ApiService
import com.example.coffeestock.sync.testutil.FakeApiService
import com.example.coffeestock.sync.testutil.FakeApiService.FailMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncScenarioTest {

    private lateinit var database: AppDatabase

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { database.close() }

    private fun engine(api: ApiService) = SyncEngine(
        database = database,
        movementDao = database.movementDao(),
        syncDao = database.syncDao(),
        apiService = api,
        apiKey = "test-key",
        pullDataApplier = PullDataApplier(database)
    )

    private fun pendingMovement(
        id: String = "movement-1",
        mutationId: String = "mutation-1",
        quantity: Double = 5.0
    ) = MovementEntity(
        id = id, productId = "product-1", productName = "Arabica",
        type = "IN", quantity = quantity, batchCode = "BATCH-01",
        partner = "Supplier", note = "test", status = "DRAFT",
        serverVersion = 0L, syncState = "PENDING", pendingOp = "CREATE",
        localRev = 1L, mutationId = mutationId, isDeleted = false,
        createdAt = "2026-11-03T00:00:00Z", updatedAt = "2026-11-03T00:00:00Z"
    )

    private fun syncedMovement(
        id: String = "movement-1",
        serverVersion: Long = 1L,
        quantity: Double = 5.0
    ) = MovementEntity(
        id = id, productId = "product-1", productName = "Arabica",
        type = "IN", quantity = quantity, batchCode = "BATCH-01",
        partner = "Supplier", note = "test", status = "DRAFT",
        serverVersion = serverVersion, syncState = "SYNCED", pendingOp = "NONE",
        localRev = 1L, mutationId = "mutation-pull", isDeleted = false,
        createdAt = "2026-11-03T00:00:00Z", updatedAt = "2026-11-03T00:00:00Z"
    )

    private fun remoteMovement(
        id: String = "movement-1",
        serverVersion: Long = 1L,
        serverSeq: Long = 1L,
        isDeleted: Boolean = false,
        quantity: Double = 5.0
    ) = com.example.coffeestock.data.remote.dto.RemoteMovementDto(
        id = id, productId = "product-1", productName = "Arabica",
        type = "IN", quantity = quantity, batchCode = "BATCH-01",
        partner = "Supplier", note = "server", status = "DRAFT",
        serverVersion = serverVersion, serverSeq = serverSeq,
        isDeleted = isDeleted,
        createdAt = "2026-11-03T00:00:00Z", updatedAt = "2026-11-03T00:00:00Z"
    )

    @Test fun tc02_offlineCreateAndEdit_persistsInRoom() = runBlocking {
        database.movementDao().insertMovement(pendingMovement(quantity = 10.0))

        val firstRead = database.movementDao().getMovementById("movement-1")
        assertNotNull(firstRead)
        assertEquals(10.0, firstRead!!.quantity, 0.0)
        assertEquals("PENDING", firstRead.syncState)

        database.movementDao().insertMovement(
            firstRead.copy(
                quantity = 20.0,
                localRev = 2L,
                mutationId = "mutation-2",
                updatedAt = "2026-11-03T01:00:00Z"
            )
        )

        val afterEdit = database.movementDao().getMovementById("movement-1")!!
        assertEquals(20.0, afterEdit.quantity, 0.0)
        assertEquals("PENDING", afterEdit.syncState)
        assertEquals("CREATE", afterEdit.pendingOp)
        assertEquals(2L, afterEdit.localRev)

        val outbox = database.movementDao().getOutboxBatch()
        assertEquals(1, outbox.size)

        println("TC02: PASS — dữ liệu lưu Room offline, outbox=${outbox.size}")
    }

    @Test fun tc03_networkRecovery_secondSyncSucceeds() = runBlocking {
        database.movementDao().insertMovement(pendingMovement())
        val api = FakeApiService()

        api.failPushMode = FailMode.IO_ERROR
        val first = engine(api).synchronize()
        assertEquals(SyncResult.RETRY, first)

        val afterFail = database.movementDao().getMovementById("movement-1")!!
        assertEquals("PENDING", afterFail.syncState)

        api.failPushMode = FailMode.NONE
        val second = engine(api).synchronize()
        assertEquals(SyncResult.SUCCESS, second)

        val afterRecover = database.movementDao().getMovementById("movement-1")!!
        assertEquals("SYNCED", afterRecover.syncState)
        assertEquals("NONE", afterRecover.pendingOp)
        assertEquals(1, api.getRecordCount())

        println("TC03: PASS — fail lần 1 (RETRY) → lần 2 SUCCESS, server=${api.getRecordCount()} record")
    }

    @Test fun tc04_persistenceAcrossReopen() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "tc04-${System.currentTimeMillis()}.db"

        var db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .allowMainThreadQueries().build()
        db.movementDao().insertMovement(pendingMovement(quantity = 12.5))
        db.close()

        db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .allowMainThreadQueries().build()
        val saved = db.movementDao().getMovementById("movement-1")
        assertNotNull(saved)
        assertEquals(12.5, saved!!.quantity, 0.0)
        assertEquals("PENDING", saved.syncState)
        db.close()

        ctx.deleteDatabase(dbName)

        println("TC04: PASS — dữ liệu persist qua close/open")
    }

    @Test fun tc05_serverTimeout_preservesLocalChanges() = runBlocking {
        database.movementDao().insertMovement(pendingMovement(quantity = 7.5))
        val api = FakeApiService().apply { failPushMode = FailMode.TIMEOUT }

        val result = engine(api).synchronize()
        assertEquals(SyncResult.RETRY, result)

        val saved = database.movementDao().getMovementById("movement-1")!!
        assertEquals(7.5, saved.quantity, 0.0)
        assertEquals("PENDING", saved.syncState)
        assertEquals("mutation-1", saved.mutationId)

        println("TC05: PASS — timeout → RETRY, dữ liệu local nguyên vẹn")
    }

    @Test fun tc06_retryPreservesMutationId() = runBlocking {
        database.movementDao().insertMovement(pendingMovement(mutationId = "fixed-mutation"))
        val api = FakeApiService()
        val sentMutations = mutableListOf<String>()

        val spyApi = object : ApiService {
            override suspend fun pushChanges(
                apiKey: String, request: com.example.coffeestock.data.remote.dto.PushRequestDto
            ) = run {
                sentMutations.addAll(request.items.map { it.mutationId })
                api.pushChanges(apiKey, request)
            }
            override suspend fun pullChanges(
                apiKey: String, afterSeq: Long, limit: Int
            ) = api.pullChanges(apiKey, afterSeq, limit)
        }

        api.failPushMode = FailMode.IO_ERROR
        engine(spyApi).synchronize()

        api.failPushMode = FailMode.NONE
        engine(spyApi).synchronize()

        assertEquals(listOf("fixed-mutation", "fixed-mutation"), sentMutations)
        assertEquals(1, api.getRecordCount())          // server không tạo trùng
        assertEquals(1, api.mutationCache.size)        // 1 mutation_id

        println("TC06: PASS — 2 lần gửi cùng mutation_id, server có ${api.getRecordCount()} record")
    }

    @Test fun tc08_offlineDelete_notResurrected() = runBlocking {
        val api = FakeApiService()
        api.records["movement-1"] = remoteMovement(serverVersion = 1L, serverSeq = 1L)

        database.movementDao().insertMovement(
            syncedMovement(serverVersion = 1L)
        )

        database.movementDao().insertMovement(
            syncedMovement(serverVersion = 1L).copy(
                isDeleted = true,
                pendingOp = "DELETE",
                syncState = "PENDING",
                localRev = 2L,
                mutationId = "mutation-delete"
            )
        )

        val pushResult = engine(api).pushChanges()
        assertEquals(SyncResult.SUCCESS, pushResult)

        val serverRecord = api.getRecord("movement-1")!!
        assertEquals(2L, serverRecord.serverVersion)
        assertEquals(true, serverRecord.isDeleted)

        val localAfter = database.movementDao().getMovementById("movement-1")!!
        assertEquals("SYNCED", localAfter.syncState)
        assertEquals("NONE", localAfter.pendingOp)
        assertEquals(true, localAfter.isDeleted)

        engine(api).pullChanges()
        val localPulled = database.movementDao().getMovementById("movement-1")
        assertTrue(localPulled == null || localPulled.isDeleted)

        println("TC08: PASS — server v${serverRecord.serverVersion} tombstone, local isDeleted=${localPulled?.isDeleted}")
    }

    @Test fun tc09_multiRecordSync_allSyncedAndMeasured() = runBlocking {
        val n = 20
        val list = (1..n).map { i ->
            pendingMovement(id = "m$i", mutationId = "mut-$i", quantity = i.toDouble())
        }
        database.movementDao().insertMovements(list)

        val api = FakeApiService()
        val start = System.nanoTime()
        val result = engine(api).synchronize()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(SyncResult.SUCCESS, result)
        assertEquals(n, api.getRecordCount())

        for (i in 1..n) {
            val saved = database.movementDao().getMovementById("m$i")!!
            assertEquals("SYNCED", saved.syncState)
        }

        println("TC09: PASS — N=$n, elapsed=${elapsedMs}ms, throughput=${"%.2f".format(n.toDouble() * 1000 / elapsedMs)} rec/s")
    }
}