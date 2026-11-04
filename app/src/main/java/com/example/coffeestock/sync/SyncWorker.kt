package com.example.coffeestock.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.coffeestock.BuildConfig
import kotlinx.coroutines.CancellationException

open class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "CoffeeStockSync"
        private const val MAX_ATTEMPTS = 5
    }

    protected open fun apiKey(): String = BuildConfig.API_KEY

    protected open suspend fun synchronize(): SyncResult =
        SyncEngineFactory.create(applicationContext).synchronize()

    override suspend fun doWork(): Result {

        val startedAt = System.currentTimeMillis()
        val attempt = runAttemptCount + 1

        if (apiKey().isBlank()) {

            Log.e(
                TAG,
                "SYNC_FAILURE reason=API_KEY_NOT_CONFIGURED attempt=$attempt"
            )

            return Result.failure()
        }

        Log.i(
            TAG,
            "SYNC_START attempt=$attempt"
        )

        return try {

            val syncResult = synchronize()

            val elapsedMs =
                System.currentTimeMillis() - startedAt

            when (syncResult) {

                SyncResult.SUCCESS -> {

                    Log.i(
                        TAG,
                        "SYNC_RESULT=SUCCESS elapsed_ms=$elapsedMs attempt=$attempt"
                    )

                    Result.success()
                }

                SyncResult.RETRY -> {

                    Log.w(
                        TAG,
                        "SYNC_RESULT=RETRY elapsed_ms=$elapsedMs attempt=$attempt"
                    )

                    if (attempt >= MAX_ATTEMPTS) {

                        Log.e(
                            TAG,
                            "SYNC_RESULT=FAILED reason=MAX_ATTEMPTS elapsed_ms=$elapsedMs"
                        )

                        Result.failure()

                    } else {

                        Result.retry()
                    }
                }

                SyncResult.FAILURE -> {

                    Log.e(
                        TAG,
                        "SYNC_RESULT=FAILED reason=PERMANENT elapsed_ms=$elapsedMs attempt=$attempt"
                    )

                    Result.failure()
                }
            }

        } catch (e: CancellationException) {

            throw e

        } catch (e: Exception) {

            val elapsedMs =
                System.currentTimeMillis() - startedAt

            Log.e(
                TAG,
                "SYNC_RESULT=ERROR elapsed_ms=$elapsedMs attempt=$attempt",
                e
            )

            if (attempt >= MAX_ATTEMPTS) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}