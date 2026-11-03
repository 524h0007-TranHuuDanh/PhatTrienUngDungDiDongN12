package com.example.coffeestock.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coffeestock.data.local.AppDatabase
import com.example.coffeestock.data.local.seed.ProductSeeder
import com.example.coffeestock.data.repository.MovementRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MovementRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MovementRepository

    private val productId = "product-001"

    @Before
    fun setUp() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ProductSeeder(db.productDao()).seedIfEmpty()
        repo = MovementRepository(
            context = ctx.applicationContext,
            database = db,
            movementDao = db.movementDao(),
            productDao = db.productDao()
        )
    }

    @After
    fun tearDown() { db.close() }


    @Test
    fun createDraft_invalidType_throws() = runBlocking {
        try {
            repo.createDraft(productId, "INVALID", 5.0, "BATCH")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Loại"))
        }
    }

    @Test
    fun createDraft_zeroQuantity_throws() = runBlocking {
        try {
            repo.createDraft(productId, "IN", 0.0, "BATCH")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Số lượng"))
        }
    }

    @Test
    fun createDraft_negativeQuantity_throws() = runBlocking {
        try {
            repo.createDraft(productId, "IN", -5.0, "BATCH")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Số lượng"))
        }
    }

    @Test
    fun createDraft_inWithoutBatchCode_throws() = runBlocking {
        try {
            repo.createDraft(productId, "IN", 5.0, "")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mã lô"))
        }
    }

    @Test
    fun createDraft_outWithoutBatchCode_succeeds() = runBlocking {
        val id = repo.createDraft(productId, "OUT", 5.0, "")
        val saved = db.movementDao().getMovementById(id)!!
        assertEquals("OUT", saved.type)
        assertEquals(5.0, saved.quantity, 0.0)
        assertEquals("PENDING", saved.syncState)
        assertEquals("CREATE", saved.pendingOp)
    }

    @Test
    fun createDraft_unknownProduct_throws() = runBlocking {
        try {
            repo.createDraft("no-such-product", "IN", 5.0, "BATCH")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mặt hàng"))
        }
    }


    @Test
    fun confirmMovement_inDraft_returnsNoWarning() = runBlocking {
        val id = repo.createDraft(productId, "IN", 10.0, "BATCH-01")
        val insufficient = repo.confirmMovement(id)
        assertFalse(insufficient)

        val saved = db.movementDao().getMovementById(id)!!
        assertEquals("CONFIRMED", saved.status)
        assertEquals("PENDING", saved.syncState)
        assertEquals(2L, saved.localRev)
    }

    @Test
    fun confirmMovement_outWithSufficientStock_returnsNoWarning() = runBlocking {
        val inId = repo.createDraft(productId, "IN", 10.0, "BATCH-01")
        repo.confirmMovement(inId)

        val outId = repo.createDraft(productId, "OUT", 5.0, "")
        val insufficient = repo.confirmMovement(outId)
        assertFalse(insufficient)

        val saved = db.movementDao().getMovementById(outId)!!
        assertEquals("CONFIRMED", saved.status)
    }

    @Test
    fun confirmMovement_outWithInsufficientStock_returnsWarningButConfirms() = runBlocking {
        val inId = repo.createDraft(productId, "IN", 3.0, "BATCH-01")
        repo.confirmMovement(inId)

        val outId = repo.createDraft(productId, "OUT", 10.0, "")
        val insufficient = repo.confirmMovement(outId)
        assertTrue(insufficient)

        val saved = db.movementDao().getMovementById(outId)!!
        assertEquals("CONFIRMED", saved.status)
    }

    @Test
    fun confirmMovement_alreadyConfirmed_throws() = runBlocking {
        val id = repo.createDraft(productId, "IN", 5.0, "BATCH-01")
        repo.confirmMovement(id)

        try {
            repo.confirmMovement(id)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("nháp"))
        }
    }


    @Test
    fun updateDraft_onlyForDraft() = runBlocking {
        val id = repo.createDraft(productId, "IN", 5.0, "BATCH-01")
        repo.confirmMovement(id)

        try {
            repo.updateDraft(id, "IN", 10.0, "BATCH-01", "", "")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("nháp"))
        }
    }

    @Test
    fun updateDraft_bumpsLocalRevAndMutationId() = runBlocking {
        val id = repo.createDraft(productId, "IN", 5.0, "BATCH-01")
        val before = db.movementDao().getMovementById(id)!!

        repo.updateDraft(id, "IN", 15.0, "BATCH-01", "New partner", "Updated")

        val after = db.movementDao().getMovementById(id)!!
        assertEquals(15.0, after.quantity, 0.0)
        assertEquals("New partner", after.partner)
        assertEquals(before.localRev + 1, after.localRev)
        org.junit.Assert.assertNotEquals(before.mutationId, after.mutationId)
        assertEquals("PENDING", after.syncState)
        assertEquals("CREATE", after.pendingOp) // serverVersion vẫn 0 → CREATE
    }


    @Test
    fun deleteDraft_unsynced_hardDeletes() = runBlocking {
        val id = repo.createDraft(productId, "IN", 5.0, "BATCH-01")
        repo.deleteDraft(id)
        assertNull(db.movementDao().getMovementById(id))
    }

    @Test
    fun deleteDraft_synced_softDeletesWithTombstone() = runBlocking {
        // Tạo + giả lập đã sync bằng cách set serverVersion=1
        val id = repo.createDraft(productId, "IN", 5.0, "BATCH-01")
        db.movementDao().insertMovement(
            db.movementDao().getMovementById(id)!!.copy(
                serverVersion = 1L, syncState = "SYNCED", pendingOp = "NONE"
            )
        )

        repo.deleteDraft(id)

        val saved = db.movementDao().getMovementById(id)
        assertNotNull(saved)
        assertTrue(saved!!.isDeleted)
        assertEquals("DELETE", saved.pendingOp)
        assertEquals("PENDING", saved.syncState)
    }

    @Test
    fun deleteDraft_confirmed_throws() = runBlocking {
        val id = repo.createDraft(productId, "IN", 5.0, "BATCH-01")
        repo.confirmMovement(id)

        try {
            repo.deleteDraft(id)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Không thể xóa"))
        }
    }
}