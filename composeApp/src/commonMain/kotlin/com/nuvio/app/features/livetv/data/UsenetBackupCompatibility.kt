package com.nuvio.app.features.livetv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The phone carries TV source settings without interpreting or activating them. */
internal object UsenetBackupCompatibility {
    private const val BASE = "usenet_sources_v1"
    private val KEY = Regex("""^usenet_sources_v1_p[1-9][0-9]*$""")

    fun isKey(key: String): Boolean = KEY.matches(key) && key.substringAfterLast("_p").toIntOrNull() != null

    fun isReserved(key: String): Boolean = key.startsWith(BASE)

    /** Validate only the portable shape; the TV owns the source configuration's schema. */
    fun validate(data: JsonObject) {
        data.forEach { (key, value) ->
            if (isReserved(key)) configuration(key, value as? JsonObject)
        }
    }

    fun hasSources(data: JsonObject): Boolean = data.any { (key, value) ->
        isKey(key) && runCatching {
            val config = configuration(key, value as? JsonObject)
            listOf("providers", "indexers").any { (config[it] as? JsonArray)?.isNotEmpty() == true }
        }.getOrDefault(false)
    }

    private fun configuration(key: String, entry: JsonObject?): JsonObject {
        require(isKey(key)) { "Invalid Usenet backup profile key" }
        val typed = requireNotNull(entry) { "Invalid Usenet backup entry" }
        require(typed["t"] == JsonPrimitive("str")) { "Invalid Usenet backup type" }
        val raw = typed["v"] as? JsonPrimitive
        require(raw != null && raw.isString) { "Invalid Usenet backup value" }
        val config = runCatching { Json.parseToJsonElement(raw.content) }.getOrElse {
            throw IllegalArgumentException("Invalid Usenet backup configuration")
        } as? JsonObject
            ?: throw IllegalArgumentException("Invalid Usenet backup configuration")
        listOf("providers", "indexers").forEach { name ->
            config[name]?.let { list ->
                require(list is JsonArray && list.all { it is JsonObject }) { "Invalid Usenet backup source list" }
            }
        }
        return config
    }
}
