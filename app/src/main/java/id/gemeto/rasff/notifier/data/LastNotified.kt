package id.gemeto.rasff.notifier.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class LastNotified(
    @PrimaryKey val uid: String,
    @ColumnInfo(name = "last_warning_title") val firstItemTitle: String?
)