package com.ramatafu.trafficmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BlockedAppEntity::class, KnownDomainEntity::class, BlockedDomainEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun knownDomainDao(): KnownDomainDao
    abstract fun blockedDomainDao(): BlockedDomainDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "traffic_monitor.db"
                )
                    // Схема поменялась (добавилась таблица доменов) — миграций пока
                    // не пишем, при повышении версии база просто пересоздаётся.
                    // Значит, старый список заблокированных приложений на этом
                    // обновлении один раз сотрётся — для MVP это приемлемо.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}
