package com.dji.recreate2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class GpsTagItem(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: Long,
    val source: String
)

object GpsTaggingManager {

    private const val PREFS_NAME = "TacticalHUDConfig"
    private const val KEY_GPS_TAGS = "gps_tagged_coordinates"

    fun getTags(context: Context): MutableList<GpsTagItem> {
        val list = mutableListOf<GpsTagItem>()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_GPS_TAGS, "[]") ?: "[]"
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    GpsTagItem(
                        id = obj.optString("id", "TAG-${i + 1}"),
                        name = obj.optString("name", "Target ${i + 1}"),
                        latitude = obj.optDouble("latitude", 0.0),
                        longitude = obj.optDouble("longitude", 0.0),
                        altitude = obj.optDouble("altitude", 0.0),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        source = obj.optString("source", "MANUAL")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addTag(
        context: Context,
        name: String,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        source: String = "MANUAL"
    ): GpsTagItem {
        val list = getTags(context)
        val maxNum = list.mapNotNull { it.id.removePrefix("TAG-").toIntOrNull() }.maxOrNull() ?: 0
        val newId = "TAG-${String.format("%03d", maxNum + 1)}"
        val tag = GpsTagItem(
            id = newId,
            name = name,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            timestamp = System.currentTimeMillis(),
            source = source
        )
        list.add(0, tag) // Add newest at the top
        saveTags(context, list)
        return tag
    }

    fun deleteTag(context: Context, tagId: String) {
        val list = getTags(context)
        list.removeAll { it.id == tagId }
        saveTags(context, list)
    }

    fun clearAllTags(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_GPS_TAGS).apply()
    }

    private fun saveTags(context: Context, list: List<GpsTagItem>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("latitude", item.latitude)
                put("longitude", item.longitude)
                put("altitude", item.altitude)
                put("timestamp", item.timestamp)
                put("source", item.source)
            }
            array.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GPS_TAGS, array.toString()).apply()
    }
}
