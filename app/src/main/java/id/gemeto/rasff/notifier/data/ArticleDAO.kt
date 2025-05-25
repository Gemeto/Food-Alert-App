package id.gemeto.rasff.notifier.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArticleDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(articles: List<Article>)

    @Query("SELECT * FROM articles")
    suspend fun getAll(): List<Article>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun getById(id: String): Article?
}
