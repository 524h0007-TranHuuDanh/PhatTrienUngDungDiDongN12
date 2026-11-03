package com.example.coffeestock.sync

import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coffeestock.data.local.AppDatabase
import com.example.coffeestock.data.local.entity.MovementEntity
import com.example.coffeestock.sync.testutil.FakeApiService
import com.example.coffeestock.sync.testutil.FakeApiService.FailMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncMetricsTest {

    private fun freshDb(): AppDatabase {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    private fun engine(db: AppDatabase, api: FakeApiService) = SyncEngine(
        database = db,
        movementDao = db.movementDao(),
        syncDao = db.syncDao(),
        apiService = api,
        apiKey = "metrics-key",
        pullDataApplier = PullDataApplier(db)
    )

    private fun pendingMovement(
        id: String, mutationId: String, quantity: Double
    ) = MovementEntity(
        id = id, productId = "product-1", productName = "Arabica",
        type = "IN", quantity = quantity, batchCode = "BATCH",
        partner = "Supplier", note = "metrics", status = "DRAFT",
        serverVersion = 0L, syncState = "PENDING", pendingOp = "CREATE",
        localRev = 1L, mutationId = mutationId, isDeleted = false,
        createdAt = "2026-11-03T00:00:00Z", updatedAt = "2026-11-03T00:00:00Z"
    )

    @Test fun metric1_syncTimeByDataSize() = runBlocking {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("========================================================")
        sb.appendLine("SYNC METRICS — CoffeeStock")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("SDK: ${Build.VERSION.SDK_INT}")
        sb.appendLine("========================================================")
        sb.appendLine()
        sb.appendLine("[Metric 1] Thời gian sync theo số bản ghi")
        sb.appendLine("N\telapsed(ms)\tper-record(ms)")
        sb.appendLine("---\t----------\t--------------")

        for (n in listOf(1, 5, 10, 25, 50)) {
            val db = freshDb()
            val list = (1..n).map { i ->
                pendingMovement("m$i", "mut-$i", i.toDouble())
            }
            db.movementDao().insertMovements(list)
            val api = FakeApiService()

            val start = System.nanoTime()
            val result = engine(db, api).synchronize()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertEquals(SyncResult.SUCCESS, result)
            assertEquals(n, api.getRecordCount())

            sb.appendLine("$n\t$elapsedMs\t\t${"%.2f".format(elapsedMs.toDouble() / n)}")
            db.close()
        }

        sb.appendLine()
        println(sb.toString())
    }

    @Test fun metric2_successRate_30PercentFailure() = runBlocking {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("[Metric 2] Tỷ lệ thành công khi 30% request bị lỗi mạng")
        sb.appendLine("Iter\tResult\tServerRecords")
        sb.appendLine("----\t------\t-------------")

        val total = 20
        var success = 0
        var retry = 0

        for (i in 1..total) {
            val db = freshDb()
            db.movementDao().insertMovement(
                pendingMovement("m-$i", "mut-$i", 1.0)
            )
            val api = FakeApiService()
            // Deterministic: 3/10 = 30% fail
            api.failPushMode = if (i % 10 < 3) FailMode.IO_ERROR else FailMode.NONE

            val result = engine(db, api).synchronize()
            if (result == SyncResult.SUCCESS) success++ else retry++
            sb.appendLine("$i\t$result\t${api.getRecordCount()}")
            db.close()
        }

        sb.appendLine()
        sb.appendLine("KẾT QUẢ: SUCCESS=$success, RETRY=$retry, rate=${"%.1f".format(100.0 * success / total)}%")
        sb.appendLine()

        println(sb.toString())
        assertTrue(success > 0)
    }

    @Test fun metric3_duplicateRate_idempotency() = runBlocking {
        val db = freshDb()
        val api = FakeApiService()

        db.movementDao().insertMovement(
            pendingMovement("m1", "fixed-mutation", 5.0)
        )

        engine(db, api).synchronize()
        assertEquals(1, api.getRecordCount())

        val current = db.movementDao().getMovementById("m1")!!
        db.movementDao().insertMovement(
            current.copy(
                syncState = "PENDING",
                pendingOp = "CREATE",
                localRev = current.localRev + 1
            )
        )

        repeat(5) { engine(db, api).synchronize() }

        val serverCount = api.getRecordCount()
        val localCount = db.movementDao().getAllMovements().first().size

        println()
        println("[Metric 3] Idempotency — gửi lại 5 lần cùng mutation_id")
        println("Server records: $serverCount (expected 1)")
        println("Local records : $localCount (expected 1)")
        println("Duplicates    : ${serverCount - 1}")
        println()

        assertEquals(1, serverCount)
        assertEquals(1, localCount)

        db.close()
    }
}