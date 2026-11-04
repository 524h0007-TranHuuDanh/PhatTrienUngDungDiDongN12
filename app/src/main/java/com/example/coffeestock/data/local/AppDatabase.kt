package com.example.coffeestock.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.coffeestock.data.local.dao.MovementDao
import com.example.coffeestock.data.local.dao.ProductDao
import com.example.coffeestock.data.local.dao.SyncDao
import com.example.coffeestock.data.local.entity.ConflictEntity
import com.example.coffeestock.data.local.entity.MovementEntity
import com.example.coffeestock.data.local.entity.ProductEntity
import com.example.coffeestock.data.local.entity.SyncMetaEntity

@Database(
    entities = [
        ProductEntity::class,
        MovementEntity::class,
        ConflictEntity::class,
        SyncMetaEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun productDao(): ProductDao

    abstract fun movementDao(): MovementDao

    abstract fun syncDao(): SyncDao

    companion object {

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_stock_movement_syncState " +
                        "ON stock_movement (syncState)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_stock_movement_pendingOp " +
                        "ON stock_movement (pendingOp)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_stock_movement_productId " +
                        "ON stock_movement (productId)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "coffeestock_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}