package com.eaglepoint.libops.data.db.converters

import androidx.room.TypeConverter
import java.time.Instant

class AppConverters {
    @TypeConverter
    fun instantToLong(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @TypeConverter
    fun stringListToJson(list: List<String>?): String? = list?.joinToString("\u001F")

    @TypeConverter
    fun jsonToStringList(value: String?): List<String>? =
        value?.split('\u001F')?.filter { it.isNotEmpty() }
}
