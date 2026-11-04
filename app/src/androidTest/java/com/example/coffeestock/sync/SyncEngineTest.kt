package com.example.coffeestock.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coffeestock.data.local.AppDatabase
import com.example.coffeestock.data.local.entity.MovementEntity
import com.example.coffeestock.data.remote.ApiService
import com.example.coffeestock.data.remote.dto.PullResponseDto
import com.example.coffeestock.data.remote.dto.PushRequestDto
import com.example.coffeestock.data.remote.dto.PushResponseDto
import com.example.coffeestock.data.remote.dto.PushResultDto
import com.example.coffeestock.data.remote.dto.RemoteMovementDto
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context =
            InstrumentationRegistry.getInstrumentation().targetContext

        database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun synchronize_pushesPendingMovementAndPullsSuccessfully() =
        runBlocking {

            val local = movement()

            database.movementDao().insertMovement(local)

            val api = FakeApiService(
                push = { request ->
                    val sent = request.items.single()

                    assertEquals(local.id, sent.id)
                    assertEquals(local.mutationId, sent.mutationId)

                    Response.success(
                        PushResponseDto(
                            results = listOf(
                                PushResultDto(
                                    id = local.id,
                                    mutationId = local.mutationId,
                                    ok = true,
                                    movement = remoteMovement(
                                        serverVersion = 2
                                    ),
                                    error = null
                                )
                            )
                        )
                    )
                },

                pull = { _, _ ->
                    Response.success(
                        PullResponseDto(
                            items = emptyList(),
                            nextCursor = 0L,
                            hasMore = false
                        )
                    )
                }
            )

            val result = engine(api).synchronize()

            assertEquals(SyncResult.SUCCESS, result)

            val saved =
                database.movementDao().getMovementById(local.id)

            assertNotNull(saved)
            assertEquals("SYNCED", saved?.syncState)
            assertEquals("NONE", saved?.pendingOp)
            assertEquals(2L, saved?.serverVersion)
        }

    @Test
    fun synchronize_recoversMovementLeftSyncingByInterruptedWorker() =
        runBlocking {

            val interruptedMovement = movement().copy(
                syncState = "SYNCING"
            )
            database.movementDao().insertMovement(interruptedMovement)

            var pushCalled = false
            val api = FakeApiService(
                push = { request ->
                    pushCalled = true
                    assertEquals(
                        interruptedMovement.id,
                        request.items.single().id
                    )

                    Response.success(
                        PushResponseDto(
                            results = listOf(
                                PushResultDto(
                                    id = interruptedMovement.id,
                                    mutationId = interruptedMovement.mutationId,
                                    ok = true,
                                    movement = remoteMovement(serverVersion = 2),
                                    error = null
                                )
                            )
                        )
                    )
                },
                pull = { _, _ ->
                    Response.success(
                        PullResponseDto(
                            items = emptyList(),
                            nextCursor = 0L,
                            hasMore = false
                        )
                    )
                }
            )

            val result = engine(api).synchronize()

            assertEquals(SyncResult.SUCCESS, result)
            assertTrue("Interrupted movement should be pushed again", pushCalled)

            val recovered =
                database.movementDao().getMovementById(interruptedMovement.id)
            assertNotNull(recovered)
            assertEquals("SYNCED", recovered?.syncState)
            assertEquals("NONE", recovered?.pendingOp)
            assertEquals(2L, recovered?.serverVersion)
        }

    @Test
    fun pushNetworkFailureReturnsRetryAndRestoresPendingState() =
        runBlocking {

            val local = movement()

            database.movementDao().insertMovement(local)

            val api = FakeApiService(
                push = {
                    throw IOException("network unavailable")
                },

                pull = { _, _ ->
                    Response.success(
                        PullResponseDto(
                            items = emptyList(),
                            nextCursor = 0L,
                            hasMore = false
                        )
                    )
                }
            )

            val result = engine(api).pushChanges()

            assertEquals(SyncResult.RETRY, result)

            val saved =
                database.movementDao().getMovementById(local.id)

            assertEquals("PENDING", saved?.syncState)
            assertEquals("CREATE", saved?.pendingOp)
        }

    @Test
    fun pullInsertsRemoteMovementAndAdvancesCursor() =
        runBlocking {

            val remote = remoteMovement(
                serverVersion = 3,
                serverSeq = 12
            )

            val api = FakeApiService(
                push = {
                    Response.success(
                        PushResponseDto(
                            results = emptyList()
                        )
                    )
                },

                pull = { afterSeq, _ ->
                    assertEquals(0L, afterSeq)

                    Response.success(
                        PullResponseDto(
                            items = listOf(remote),
                            nextCursor = 12L,
                            hasMore = false
                        )
                    )
                }
            )

            val result = engine(api).pullChanges()

            assertEquals(SyncResult.SUCCESS, result)

            val saved =
                database.movementDao().getMovementById(remote.id)

            assertNotNull(saved)
            assertEquals("NONE", saved?.pendingOp)
            assertEquals("SYNCED", saved?.syncState)
            assertEquals(3L, saved?.serverVersion)

            assertEquals(
                "12",
                database.syncDao().getSyncMeta("movements_last_seq")
            )
        }

    @Test
    fun pullHttpErrorReturnsRetry() = runBlocking {

        val api = FakeApiService(
            push = {
                Response.success(
                    PushResponseDto(
                        results = emptyList()
                    )
                )
            },

            pull = { _, _ ->
                Response.error(
                    503,
                    "server unavailable".toResponseBody(
                        "text/plain".toMediaType()
                    )
                )
            }
        )

        val result = engine(api).pullChanges()

        assertEquals(SyncResult.RETRY, result)

        assertNull(
            database.movementDao().getMovementById("movement-1")
        )
    }

    private fun engine(api: ApiService): SyncEngine {
        return SyncEngine(
            database = database,
            movementDao = database.movementDao(),
            syncDao = database.syncDao(),
            apiService = api,
            apiKey = "test-api-key",
            pullDataApplier = PullDataApplier(database)
        )
    }

    private fun movement() = MovementEntity(
        id = "movement-1",
        productId = "product-1",
        productName = "Arabica",
        type = "IMPORT",
        quantity = 5.0,
        batchCode = "BATCH-01",
        partner = "Supplier",
        note = "Test movement",
        status = "DRAFT",
        serverVersion = 1L,
        syncState = "PENDING",
        pendingOp = "CREATE",
        localRev = 1L,
        mutationId = "mutation-1",
        isDeleted = false,
        createdAt = "2026-11-03T00:00:00Z",
        updatedAt = "2026-11-03T00:00:00Z"
    )

    private fun remoteMovement(
        serverVersion: Long = 1L,
        serverSeq: Long = 1L
    ) = RemoteMovementDto(
        id = "movement-1",
        productId = "product-1",
        productName = "Arabica",
        type = "IMPORT",
        quantity = 5.0,
        batchCode = "BATCH-01",
        partner = "Supplier",
        note = "From server",
        status = "CONFIRMED",
        serverVersion = serverVersion,
        serverSeq = serverSeq,
        isDeleted = false,
        createdAt = "2026-11-03T00:00:00Z",
        updatedAt = "2026-11-03T00:00:00Z"
    )

    private class FakeApiService(
        private val push:
        suspend (PushRequestDto) -> Response<PushResponseDto>,

        private val pull:
        suspend (Long, Int) -> Response<PullResponseDto>

    ) : ApiService {

        override suspend fun pushChanges(
            apiKey: String,
            request: PushRequestDto
        ): Response<PushResponseDto> {
            return push(request)
        }

        override suspend fun pullChanges(
            apiKey: String,
            afterSeq: Long,
            limit: Int
        ): Response<PullResponseDto> {
            return pull(afterSeq, limit)
        }
    }
}