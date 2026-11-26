package com.nuvio.app.features.livetv.data

import com.nuvio.vpn.android.VpnBackup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Local encryption only. The original typed string is what a portable backup exports. */
internal object LiveTvBackupSecretsAndroid {
    private const val PREFIX = "nuvio.backup.local.v1:"
    private val cached = HashMap<String, Pair<PrefValue.Str, String>>()

    fun isSealed(value: String): Boolean = value.startsWith(PREFIX)

    @Synchronized
    fun seal(key: String, value: PrefValue, stored: String? = null, protect: (String) -> String = VpnBackup::sealCache): String {
        require(value is PrefValue.Str) { "Invalid Usenet backup value" }
        cached[key]?.takeIf { it.first == value && it.second == stored }?.let { return it.second }
        val data = buildJsonObject {
            put(key, buildJsonObject { put("t", "str"); put("v", value.value) })
        }
        UsenetBackupCompatibility.validate(data)
        return (PREFIX + protect(data.toString())).also { cached[key] = value to it }
    }

    @Synchronized
    fun open(key: String, stored: String, unprotect: (String) -> String = VpnBackup::openCache): PrefValue {
        if (!isSealed(stored)) return PrefValue.Str(stored) // Migrated by the preference reader.
        return try {
            val data = parseObject(unprotect(stored.removePrefix(PREFIX)))
            UsenetBackupCompatibility.validate(data)
            val entry = data[key] as? JsonObject ?: error("Missing protected entry")
            PrefValue.Str(entry.optString("v")).also { cached[key] = it to stored }
        } catch (_: Exception) {
            cached.remove(key)
            // Keep IPTV usable while the portable exporter refuses this redacted local marker.
            PrefValue.UnavailableBackupSecret(stored)
        }
    }
}
