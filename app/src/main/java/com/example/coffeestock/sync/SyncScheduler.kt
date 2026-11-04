package com.example.coffeestock.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val SYNC_WORK_NAME = "coffeestock_sync"

    fun enqueueSync(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        enqueueSync { name, policy, request ->
            workManager.enqueueUniqueWork(name, policy, request)
        }
    }

    internal fun enqueueSync(
        enqueueUniqueWork: (String, ExistingWorkPolicy, OneTimeWorkRequest) -> Unit
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()

        enqueueUniqueWork(
            SYNC_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncRequest
        )
    }
}