package id.gemeto.rasff.notifier.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [LastNotified::class, Article::class], version = 2)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lastNotifiedDao(): LastNotifiedDAO
    abstract fun articleDao(): ArticleDAO
}