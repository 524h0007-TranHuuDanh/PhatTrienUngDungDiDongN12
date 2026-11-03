package com.example.coffeestock.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.coffeestock.data.local.entity.MovementEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MovementItemTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testMovementItemDisplaysCorrectDataAndSyncStatus() {
        val mockMovement = MovementEntity(
            id = "m1",
            productId = "1",
            productName = "Arabica",
            type = "IN",
            quantity = 5.0,
            batchCode = "BATCH-01",
            status = "DRAFT",
            syncState = "PENDING",
            pendingOp = "CREATE",
            localRev = 1,
            mutationId = "mut-1",
            createdAt = "2026-11-03",
            updatedAt = "2026-11-03"
        )

        composeTestRule.setContent {
            MovementItem(movement = mockMovement)
        }

        composeTestRule.onNodeWithText("Arabica").assertIsDisplayed()
        composeTestRule.onNodeWithText("Loại phiếu: Nhập kho").assertIsDisplayed()
        composeTestRule.onNodeWithText("Số lượng: 5.0 kg").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mã lô: BATCH-01").assertIsDisplayed()
        composeTestRule.onNodeWithText("Trạng thái: Nháp").assertIsDisplayed()

        composeTestRule.onNodeWithText("Đồng bộ: Đang chờ").assertIsDisplayed()
    }
}