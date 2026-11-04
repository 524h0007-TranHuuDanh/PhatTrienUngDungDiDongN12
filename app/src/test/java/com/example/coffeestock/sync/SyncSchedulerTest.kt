package com.example.coffeestock.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerTest {

    @Test
    fun enqueueSync_UsesSameUniqueWorkChainForRepeatedRequests() {
        val enqueuedWork = mutableListOf<Triple<String, ExistingWorkPolicy, OneTimeWorkRequest>>()
        val enqueue: (String, ExistingWorkPolicy, OneTimeWorkRequest) -> Unit =
            { name, policy, request ->
                enqueuedWork += Triple(name, policy, request)
            }

        SyncScheduler.enqueueSync(enqueue)
        SyncScheduler.enqueueSync(enqueue)

        assertEquals(2, enqueuedWork.size)
        assertEquals(
            listOf(
                "coffeestock_sync" to ExistingWorkPolicy.APPEND_OR_REPLACE,
                "coffeestock_sync" to ExistingWorkPolicy.APPEND_OR_REPLACE
            ),
            enqueuedWork.map { it.first to it.second }
        )
    }
}
