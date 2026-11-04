package com.example.coffeestock.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testSyncWorker_NoApiKey_ReturnsFailure() = runBlocking {
        val worker = buildWorker(apiKey = "")

        val result = worker.doWork()

        assertTrue(
            "Expected failure when API key is blank",
            result is ListenableWorker.Result.Failure
        )
    }

    @Test
    fun testSyncWorker_RetryResultBeforeMaxAttempts_ReturnsRetry() = runBlocking {
        val worker = buildWorker(
            syncResult = SyncResult.RETRY,
            runAttemptCount = 3
        )

        val result = worker.doWork()

        assertTrue(
            "Expected retry on attempt 4 of 5",
            result is ListenableWorker.Result.Retry
        )
    }

    @Test
    fun testSyncWorker_RetryResultAtMaxAttempts_ReturnsFailure() = runBlocking {
        val worker = buildWorker(
            syncResult = SyncResult.RETRY,
            runAttemptCount = 4
        )

        val result = worker.doWork()

        assertTrue(
            "Expected failure on attempt 5 of 5",
            result is ListenableWorker.Result.Failure
        )
    }

    private fun buildWorker(
        apiKey: String = "test-api-key",
        syncResult: SyncResult = SyncResult.SUCCESS,
        runAttemptCount: Int = 0
    ): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker? {
                    if (workerClassName != SyncWorker::class.java.name) {
                        return null
                    }

                    return object : SyncWorker(appContext, workerParameters) {
                        override fun apiKey() = apiKey

                        override suspend fun synchronize() = syncResult
                    }
                }
            })
            .build()
}
