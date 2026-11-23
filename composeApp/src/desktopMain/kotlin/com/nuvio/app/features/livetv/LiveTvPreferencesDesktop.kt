package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.LiveTvDirs
import com.nuvio.app.features.livetv.data.LiveTvFiles
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.PrefValue
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal object LiveTvPreferencesDesktop {
    private val lock = SynchronizedObject()
    private var initialized = false
    private const val preferences = "preferences.json"
    private const val devicePreferences = "device-preferences.json"

    fun initialize() {
        if (initialized) return
        initialized = true
        LiveTvPreferences.installPersistence(
            load = { load(preferences) },
            save = { synchronized(lock) { save(preferences, LiveTvPreferences.rawData.value) } },
            loadDevice = { load(devicePreferences) },
            saveDevice = { synchronized(lock) { save(devicePreferences, LiveTvPreferences.rawDevice.value) } },
        )
        LiveTvStartup.start()
    }

    private fun load(name: String): Map<String, PrefValue>? = runCatching {
        val text = LiveTvFiles.readSmall(LiveTvDirs.STATE, name) ?: return null
        val items = Json.parseToJsonElement(text) as? JsonObject ?: return null
        buildMap {
            items.forEach { (key, stored) ->
                val pair = stored as? JsonArray ?: return@forEach
                val type = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull
                val data = pair.getOrNull(1)
                val value = data as? JsonPrimitive
                val decoded = when (type) {
                    "bool" -> value?.booleanOrNull?.let { PrefValue.Bool(it) }
                    "int" -> value?.intOrNull?.let { PrefValue.Int(it) }
                    "long" -> value?.longOrNull?.let { PrefValue.Long(it) }
                    "string" -> value?.contentOrNull?.let { PrefValue.Str(it) }
                    "set" -> (data as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?.toSet()?.let { PrefValue.StringSet(it) }
                    else -> null
                }
                if (decoded != null) put(key, decoded)
            }
        }
    }.getOrNull()

    private fun save(name: String, values: Map<String, PrefValue>) {
        val json = JsonObject(values.mapValues { (_, value) ->
            val (tag, data) = when (value) {
                is PrefValue.Bool -> "bool" to JsonPrimitive(value.value)
                is PrefValue.Int -> "int" to JsonPrimitive(value.value)
                is PrefValue.Long -> "long" to JsonPrimitive(value.value)
                is PrefValue.Str -> "string" to JsonPrimitive(value.value)
                is PrefValue.StringSet -> "set" to JsonArray(value.value.map { JsonPrimitive(it) })
            }
            JsonArray(listOf(JsonPrimitive(tag), data))
        })
        LiveTvFiles.writeSmall(LiveTvDirs.STATE, name, json.toString())
    }
}
