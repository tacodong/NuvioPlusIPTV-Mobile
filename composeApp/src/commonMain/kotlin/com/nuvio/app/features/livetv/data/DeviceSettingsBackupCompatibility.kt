package com.nuvio.app.features.livetv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** TV hardware choices are retained for explicit backup round trips, never applied on the phone. */
internal object DeviceSettingsBackupCompatibility {
    const val KEY = "device_settings_v1"

    fun containsValidSettings(data: JsonObject): Boolean {
        if (!data.containsKey(KEY)) return false
        try {
            val entry = data[KEY] as? JsonObject ?: error("entry")
            require(entry["t"] == JsonPrimitive("str"))
            val raw = entry["v"] as? JsonPrimitive ?: error("value")
            require(raw.isString)
            val value = Json.parseToJsonElement(raw.content) as? JsonObject ?: error("payload")
            require(value["version"] == JsonPrimitive(1))
            require(value["liveTv"] is JsonObject && value["playback"] is JsonObject)
            return true
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid TV device settings backup")
        }
    }
}
