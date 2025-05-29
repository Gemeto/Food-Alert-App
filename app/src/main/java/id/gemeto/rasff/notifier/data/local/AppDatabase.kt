package id.gemeto.rasff.notifier.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import id.gemeto.rasff.notifier.data.local.Converters
import id.gemeto.rasff.notifier.data.local.dao.ArticleDAO
import id.gemeto.rasff.notifier.data.local.dao.LastNotifiedDAO
import id.gemeto.rasff.notifier.data.local.entity.Article
import id.gemeto.rasff.notifier.data.local.entity.LastNotified

@Database(entities = [LastNotified::class, Article::class], version = 2)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lastNotifiedDao(): LastNotifiedDAO
    abstract fun articleDao(): ArticleDAO
}