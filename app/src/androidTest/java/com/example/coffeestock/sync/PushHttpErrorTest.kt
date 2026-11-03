package com.example.coffeestock.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coffeestock.data.local.AppDatabase
import com.example.coffeestock.data.local.entity.MovementEntity
import com.example.coffeestock.sync.testutil.FakeApiService
import com.example.coffeestock.sync.testutil.FakeApiService.FailMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushHttpErrorTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() { database.close() }

    private fun engine(api: FakeApiService) = SyncEngine(
        database = database,
        movementDao = database.movementDao(),
        syncDao = database.syncDao(),
        apiService = api,
        apiKey = "test-key",
        pullDataApplier = PullDataApplier(database)
    )

    private fun pendingMovement(
        id: String = "movement-1",
        mutationId: String = "mutation-1"
    ) = MovementEntity(
        id = id, productId = "product-1", productName = "Arabica",
        type = "IN", quantity = 5.0, batchCode = "BATCH-01",
        partner = "Supplier", note = "test", status = "DRAFT",
        serverVersion = 0L, syncState = "PENDING", pendingOp = "CREATE",
        localRev = 1L, mutationId = mutationId, isDeleted = false,
        createdAt = "2026-11-03T00:00:00Z", updatedAt = "2026-11-03T00:00:00Z"
    )

    private fun assertRetryablePushHttpError(mode: FailMode) = runBlocking {
        database.movementDao().insertMovement(pendingMovement())
        val api = FakeApiService().apply { failPushMode = mode }

        val result = engine(api).pushChanges()
        assertEquals(SyncResult.RETRY, result)

        val saved = database.movementDao().getMovementById("movement-1")!!
        assertEquals("PENDING", saved.syncState)
        assertEquals("CREATE", saved.pendingOp)
        assertEquals(0, api.getRecordCount())
    }

    @Test
    fun pushHttp500_returnsRetry() = assertRetryablePushHttpError(FailMode.HTTP_500)

    @Test
    fun pushHttp503_returnsRetry() = assertRetryablePushHttpError(FailMode.HTTP_503)

    @Test
    fun pushHttp429_returnsRetry() = assertRetryablePushHttpError(FailMode.HTTP_429)

    @Test
    fun pushHttp408_returnsRetry() = assertRetryablePushHttpError(FailMode.HTTP_408)

    @Test
    fun pushHttp500RecoversOnRetry() = runBlocking {
        database.movementDao().insertMovement(pendingMovement())
        val api = FakeApiService().apply { failPushMode = FailMode.HTTP_500 }

        assertEquals(SyncResult.RETRY, engine(api).pushChanges())
        assertEquals("PENDING", database.movementDao().getMovementById("movement-1")!!.syncState)

        api.failPushMode = FailMode.NONE
        assertEquals(SyncResult.SUCCESS, engine(api).pushChanges())

        val saved = database.movementDao().getMovementById("movement-1")!!
        assertEquals("SYNCED", saved.syncState)
        assertEquals("NONE", saved.pendingOp)
        assertEquals(1L, saved.serverVersion)
        assertEquals(1, api.getRecordCount())
    }

    @Test
    fun pullHttp429_returnsRetry() = runBlocking {
        val api = FakeApiService().apply { failPullMode = FailMode.HTTP_429 }
        assertEquals(SyncResult.RETRY, engine(api).pullChanges())
    }

    @Test
    fun pullHttp408_returnsRetry() = runBlocking {
        val api = FakeApiService().apply { failPullMode = FailMode.HTTP_408 }
        assertEquals(SyncResult.RETRY, engine(api).pullChanges())
    }

    @Test
    fun pullHttp500_returnsRetry() = runBlocking {
        val api = FakeApiService().apply { failPullMode = FailMode.HTTP_500 }
        assertEquals(SyncResult.RETRY, engine(api).pullChanges())
    }
}