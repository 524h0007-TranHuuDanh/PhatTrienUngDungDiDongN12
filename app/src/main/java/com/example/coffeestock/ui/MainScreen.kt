
package com.example.coffeestock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.coffeestock.data.local.entity.MovementEntity
import com.example.coffeestock.data.repository.ProductStock
import kotlinx.coroutines.launch
import com.example.coffeestock.viewmodel.CoffeeStockViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
@Composable
fun MainScreen(viewModel: CoffeeStockViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow("coffeestock_sync")
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val latestWorkInfo = workInfos.lastOrNull()
    val syncMessage = when (latestWorkInfo?.state) {
        WorkInfo.State.ENQUEUED -> "Đang chờ điều kiện chạy"
        WorkInfo.State.BLOCKED -> "Đang chờ tác vụ trước"
        WorkInfo.State.RUNNING -> "Đang đồng bộ..."
        WorkInfo.State.SUCCEEDED -> "Đồng bộ thành công"
        WorkInfo.State.FAILED -> "Đồng bộ thất bại"
        WorkInfo.State.CANCELLED -> "Tác vụ đã bị hủy"
        null -> "Chưa có tác vụ đồng bộ"
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(syncMessage, style = MaterialTheme.typography.bodyMedium)
                        Text("Chờ đồng bộ: ${state.pendingCount}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Thất bại: ${state.failedCount} | Xung đột: ${state.conflictCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.failedCount > 0 || state.conflictCount > 0)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = {
                        coroutineScope.launch { viewModel.syncNow() }
                    }) { Text("Đồng bộ ngay") }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selectedTab == 0, { selectedTab = 0 }, icon = { Text("⌂") }, label = { Text("Tổng quan") })
                NavigationBarItem(selectedTab == 1, { selectedTab = 1 }, icon = { Text("▤") }, label = { Text("Kho hàng") })
                NavigationBarItem(selectedTab == 2, { selectedTab = 2 }, icon = { Text("⇄") }, label = { Text("Phiếu kho") })
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(state.products, state.movements)
                1 -> InventoryScreen(state.products)
                2 -> MovementScreen(state.products, state.movements, viewModel)
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    products: List<ProductStock>,
    movements: List<MovementEntity>
) {
    val totalProducts = products.size
    val lowStockProducts = products.count { it.isLowStock }

    val stockByUnit = products
        .groupBy { it.unit }
        .mapValues { entry ->
            entry.value.sumOf { it.currentStock }
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = "CoffeeStock",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Tổng quan kho hàng",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SummaryCard(
                title = "Sản phẩm",
                value = totalProducts.toString(),
                modifier = Modifier.weight(1f)
            )

            SummaryCard(
                title = "Sắp hết hàng",
                value = lowStockProducts.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Tổng tồn kho",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (stockByUnit.isEmpty()) {
                    Text("Chưa có dữ liệu tồn kho.")
                } else {
                    stockByUnit.forEach { (unit, quantity) ->
                        Text(
                            text = "${String.format("%.2f", quantity)} $unit",
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Phiếu kho gần đây",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (movements.isEmpty()) {
            Text("Chưa có phiếu nhập hoặc xuất.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(movements.take(5), key = { it.id }) { movement ->
                    MovementItem(movement)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

@Composable
private fun InventoryScreen(products: List<ProductStock>) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp)
    ) {
        Text("Kho hàng", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Danh mục mặt hàng được khởi tạo cố định trên thiết bị.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (products.isEmpty()) {
            Text("Chưa có sản phẩm trong kho.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(products, key = { it.productId }) { product -> ProductItem(product) }
            }
        }
    }
}

@Composable
private fun ProductItem(product: ProductStock) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = product.productName,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "TỒN KHO HIỆN TẠI",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format("%.2f", product.currentStock),
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (product.isLowStock) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )

                Text(
                    text = " ${product.unit}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Ngưỡng tối thiểu: ${product.minStock} ${product.unit}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (product.isLowStock) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "⚠ Sắp hết hàng",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun MovementScreen(
    products: List<ProductStock>,
    movements: List<MovementEntity>,
    viewModel: CoffeeStockViewModel
) {
    val scope = rememberCoroutineScope()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var movementToEdit by remember { mutableStateOf<MovementEntity?>(null) }
    var movementToDelete by remember { mutableStateOf<MovementEntity?>(null) }

    var selectedProductId by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var movementType by remember { mutableStateOf("IN") }
    var quantity by remember { mutableStateOf("") }
    var batchCode by remember { mutableStateOf("") }
    var partner by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    val selectedProduct = products.find {
        it.productId == selectedProductId
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Phiếu kho",
                    style = MaterialTheme.typography.headlineMedium
                )

                Text(
                    text = "Quản lý nhập và xuất hàng",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = {
                    selectedProductId = ""
                    movementType = "IN"
                    quantity = ""
                    batchCode = ""
                    partner = ""
                    note = ""
                    errorMessage = ""
                    showCreateDialog = true
                }
            ) {
                Text("Tạo phiếu")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (errorMessage.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )

                    TextButton(onClick = { errorMessage = "" }) {
                        Text("Đóng")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        if (movements.isEmpty()) {
            Text("Chưa có phiếu kho.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(movements, key = { it.id }) { movement ->
                    MovementItem(
                        movement = movement,
                        onConfirm = {
                            scope.launch {
                                try {
                                    val insufficientStock =
                                        viewModel.confirmMovement(movement.id)

                                    errorMessage = if (insufficientStock) {
                                        "Cảnh báo: Phiếu xuất vượt quá số lượng tồn kho hiện tại."
                                    } else {
                                        ""
                                    }
                                } catch (e: Exception) {
                                    errorMessage = e.message
                                        ?: "Không thể xác nhận phiếu."
                                }
                            }
                        },
                        onEdit = {
                            movementToEdit = movement
                            movementType = movement.type
                            quantity = movement.quantity.toString()
                            batchCode = movement.batchCode
                            partner = movement.partner
                            note = movement.note
                            errorMessage = ""
                            showEditDialog = true
                        },
                        onDelete = {
                            movementToDelete = movement
                        }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Tạo phiếu kho") },
            text = {
                MovementForm(
                    products = products,
                    selectedProductId = selectedProductId,
                    onProductSelected = { selectedProductId = it },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    selectedProductName = selectedProduct?.productName,
                    movementType = movementType,
                    onTypeChange = { movementType = it },
                    quantity = quantity,
                    onQuantityChange = { quantity = it },
                    batchCode = batchCode,
                    onBatchCodeChange = { batchCode = it },
                    partner = partner,
                    onPartnerChange = { partner = it },
                    note = note,
                    onNoteChange = { note = it },
                    errorMessage = errorMessage
                )
            },
            confirmButton = {
                Button(
                    enabled = products.isNotEmpty(),
                    onClick = {
                        val amount = quantity.toDoubleOrNull()

                        if (selectedProductId.isBlank()) {
                            errorMessage = "Vui lòng chọn sản phẩm."
                        } else if (amount == null || amount <= 0) {
                            errorMessage = "Số lượng phải lớn hơn 0."
                        } else if (movementType == "IN" && batchCode.isBlank()) {
                            errorMessage = "Phiếu nhập bắt buộc có mã lô."
                        } else {
                            scope.launch {
                                try {
                                    viewModel.createDraft(
                                        productId = selectedProductId,
                                        type = movementType,
                                        quantity = amount,
                                        batchCode = batchCode,
                                        partner = partner,
                                        note = note
                                    )
                                    showCreateDialog = false
                                    errorMessage = ""
                                } catch (e: Exception) {
                                    errorMessage = e.message
                                        ?: "Không thể tạo phiếu."
                                }
                            }
                        }
                    }
                ) {
                    Text("Lưu phiếu nháp")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Hủy")
                }
            }
        )
    }

    if (showEditDialog && movementToEdit != null) {
        AlertDialog(
            onDismissRequest = {
                showEditDialog = false
                movementToEdit = null
            },
            title = { Text("Sửa phiếu nháp") },
            text = {
                Column {
                    Text("Loại phiếu")

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { movementType = "IN" }) {
                            Text(if (movementType == "IN") "✓ Nhập kho" else "Nhập kho")
                        }

                        Button(onClick = { movementType = "OUT" }) {
                            Text(if (movementType == "OUT") "✓ Xuất kho" else "Xuất kho")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Số lượng") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = batchCode,
                        onValueChange = { batchCode = it },
                        label = { Text("Mã lô hàng") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = partner,
                        onValueChange = { partner = it },
                        label = {
                            Text(if (movementType == "IN") "Nhà cung cấp" else "Người nhận")
                        },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Ghi chú") }
                    )

                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amount = quantity.toDoubleOrNull()

                        if (amount == null || amount <= 0) {
                            errorMessage = "Số lượng phải lớn hơn 0."
                        } else if (movementType == "IN" && batchCode.isBlank()) {
                            errorMessage = "Phiếu nhập bắt buộc có mã lô."
                        } else {
                            scope.launch {
                                try {
                                    val movement = movementToEdit!!

                                    viewModel.updateDraft(
                                        movementId = movement.id,
                                        type = movementType,
                                        quantity = amount,
                                        batchCode = batchCode,
                                        partner = partner,
                                        note = note
                                    )

                                    showEditDialog = false
                                    movementToEdit = null
                                    errorMessage = ""
                                } catch (e: Exception) {
                                    errorMessage = e.message
                                        ?: "Không thể sửa phiếu."
                                }
                            }
                        }
                    }
                ) {
                    Text("Lưu thay đổi")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showEditDialog = false
                        movementToEdit = null
                    }
                ) {
                    Text("Hủy")
                }
            }
        )
    }

    if (movementToDelete != null) {
        AlertDialog(
            onDismissRequest = { movementToDelete = null },
            title = { Text("Xóa phiếu nháp?") },
            text = {
                Text("Bạn có chắc chắn muốn xóa phiếu này không?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                viewModel.deleteDraft(
                                    movementToDelete!!.id
                                )
                                movementToDelete = null
                                errorMessage = ""
                            } catch (e: Exception) {
                                errorMessage = e.message
                                    ?: "Không thể xóa phiếu."
                                movementToDelete = null
                            }
                        }
                    }
                ) {
                    Text("Xóa")
                }
            },
            dismissButton = {
                TextButton(onClick = { movementToDelete = null }) {
                    Text("Hủy")
                }
            }
        )
    }
}

@Composable
private fun MovementForm(
    products: List<ProductStock>,
    selectedProductId: String,
    onProductSelected: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedProductName: String?,
    movementType: String,
    onTypeChange: (String) -> Unit,
    quantity: String,
    onQuantityChange: (String) -> Unit,
    batchCode: String,
    onBatchCodeChange: (String) -> Unit,
    partner: String,
    onPartnerChange: (String) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    errorMessage: String
) {
    Column {
        Text("Loại phiếu")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onTypeChange("IN") }) {
                Text(if (movementType == "IN") "✓ Nhập kho" else "Nhập kho")
            }

            Button(onClick = { onTypeChange("OUT") }) {
                Text(if (movementType == "OUT") "✓ Xuất kho" else "Xuất kho")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text("Sản phẩm")

        Box {
            Button(
                onClick = { onExpandedChange(true) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedProductName ?: "Chọn sản phẩm")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) }
            ) {
                products.forEach { product ->
                    DropdownMenuItem(
                        text = { Text(product.productName) },
                        onClick = {
                            onProductSelected(product.productId)
                            onExpandedChange(false)
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = quantity,
            onValueChange = onQuantityChange,
            label = { Text("Số lượng") },
            singleLine = true
        )

        OutlinedTextField(
            value = batchCode,
            onValueChange = onBatchCodeChange,
            label = { Text("Mã lô hàng") },
            singleLine = true
        )

        OutlinedTextField(
            value = partner,
            onValueChange = onPartnerChange,
            label = {
                Text(if (movementType == "IN") "Nhà cung cấp" else "Người nhận")
            },
            singleLine = true
        )

        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text("Ghi chú") }
        )

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun MovementItem(
    movement: MovementEntity,
    onConfirm: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = movement.productName,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (movement.type == "IN") {
                    "Loại phiếu: Nhập kho"
                } else {
                    "Loại phiếu: Xuất kho"
                }
            )

            Text(
                text = "Số lượng: ${movement.quantity} kg"
            )

            if (movement.batchCode.isNotBlank()) {
                Text(text = "Mã lô: ${movement.batchCode}")
            }

            if (movement.partner.isNotBlank()) {
                Text(text = "Đối tác: ${movement.partner}")
            }

            if (movement.note.isNotBlank()) {
                Text(text = "Ghi chú: ${movement.note}")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (movement.status) {
                    "DRAFT" -> "Trạng thái: Nháp"
                    "CONFIRMED" -> "Trạng thái: Đã xác nhận"
                    else -> "Trạng thái: ${movement.status}"
                },
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = when (movement.syncState) {
                    "PENDING" -> "Đồng bộ: Đang chờ"
                    "SYNCING" -> "Đồng bộ: Đang đồng bộ"
                    "SYNCED" -> "Đồng bộ: Đã đồng bộ"
                    "FAILED" -> "Đồng bộ: Thất bại"
                    "CONFLICT" -> "Đồng bộ: Có xung đột"
                    else -> "Đồng bộ: ${movement.syncState}"
                },
                style = MaterialTheme.typography.bodySmall
            )

            if (movement.status == "DRAFT") {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onConfirm != null) {
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Xác nhận")
                        }
                    }

                    if (onEdit != null) {
                        OutlinedButton(
                            onClick = onEdit,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Sửa")
                        }
                    }

                    if (onDelete != null) {
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Xóa")
                        }
                    }
                }
            }
        }
    }
}