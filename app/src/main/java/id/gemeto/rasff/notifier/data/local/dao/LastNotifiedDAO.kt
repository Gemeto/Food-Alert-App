package id.gemeto.rasff.notifier.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import id.gemeto.rasff.notifier.data.local.entity.LastNotified

@Dao
interface LastNotifiedDAO {
    @Query("SELECT * FROM lastNotified LIMIT 1")
    fun getOne(): LastNotified?

    @Insert
    fun insert(lastNotified: LastNotified)

    @Delete
    fun delete(lastNotified: LastNotified)
}