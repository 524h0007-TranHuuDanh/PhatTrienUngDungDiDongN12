package com.example.coffeestock.data.repository

import com.example.coffeestock.data.local.dao.MovementDao
import com.example.coffeestock.data.local.dao.ProductDao
import com.example.coffeestock.data.local.entity.ProductEntity
import com.example.coffeestock.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

data class ProductStock(
    val productId: String,
    val productName: String,
    val unit: String,
    val minStock: Double,
    val totalIn: Double,
    val totalOut: Double,
    val currentStock: Double,
    val isLowStock: Boolean
)

class ProductRepository(
    private val productDao: ProductDao,
    private val movementDao: MovementDao
) {

    suspend fun addProduct(
        name: String,
        unit: String,
        minStock: Double
    ) {
        require(name.isNotBlank()) {
            "Tên sản phẩm không được để trống."
        }

        require(unit.isNotBlank()) {
            "Đơn vị tính không được để trống."
        }

        require(minStock >= 0) {
            "Tồn kho tối thiểu không hợp lệ."
        }

        val product = ProductEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            unit = unit.trim(),
            minStock = minStock,
            serverVersion = 0,
            updatedAt = Clock.nowUtc()
        )

        productDao.insertProduct(product)
    }

    fun getProductsWithStock(): Flow<List<ProductStock>> {

        return combine(
            productDao.getAllProducts(),
            movementDao.getAllMovements()
        ) { products, movements ->

            products.map { product ->

                val productMovements = movements.filter {
                    it.productId == product.id &&
                            it.status == "CONFIRMED" &&
                            !it.isDeleted
                }

                val totalIn = productMovements
                    .filter { it.type == "IN" }
                    .sumOf { it.quantity }

                val totalOut = productMovements
                    .filter { it.type == "OUT" }
                    .sumOf { it.quantity }

                val currentStock = totalIn - totalOut

                ProductStock(
                    productId = product.id,
                    productName = product.name,
                    unit = product.unit,
                    minStock = product.minStock,
                    totalIn = totalIn,
                    totalOut = totalOut,
                    currentStock = currentStock,
                    isLowStock = currentStock <= product.minStock
                )
            }
        }
    }

    fun getAllProducts() =
        productDao.getAllProducts()

    suspend fun getProductById(productId: String) =
        productDao.getProductById(productId)
}