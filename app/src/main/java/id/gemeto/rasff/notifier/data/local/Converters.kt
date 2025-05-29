package id.gemeto.rasff.notifier.data.local

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromFloatList(list: List<Float>?): String? {
        return list?.joinToString(",")
    }

    @TypeConverter
    fun toFloatList(data: String?): List<Float>? {
        return data?.split(',')?.map { it.toFloat() }
    }
}