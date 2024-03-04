package id.gemeto.rasff.notifier.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [LastNotified::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lastNotifiedDao(): LastNotifiedDAO
}