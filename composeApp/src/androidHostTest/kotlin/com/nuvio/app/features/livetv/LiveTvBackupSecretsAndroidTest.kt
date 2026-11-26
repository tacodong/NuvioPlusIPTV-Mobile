package com.nuvio.vpn.android

import com.nuvio.app.features.livetv.data.LiveTvBackupSecretsAndroid
import com.nuvio.app.features.livetv.data.PrefValue
import com.nuvio.app.features.livetv.LiveTvPreferencesAndroid
import com.nuvio.app.features.livetv.BackupPreferenceProtection
import com.nuvio.app.features.livetv.data.LiveTvPreferencesStore
import com.nuvio.app.features.livetv.data.exportForSync
import android.content.Context
import android.content.SharedPreferences
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

/** Uses an injected synthetic AES key: real device Keystore availability remains untested. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveTvBackupSecretsAndroidTest {
    private val key = SecretKeySpec(ByteArray(32), "AES")
    private val profile = "usenet_sources_v1_p3"
    private val original = PrefValue.Str("""{ "providers":[{"password":"synthetic-provider-secret"}],"indexers":[{"apiKey":"synthetic-indexer-secret"}],"future":[1,2] }""")

    @Test fun preferencesKeepOnlyCiphertextAndReopenTheExactPortableString() {
        val sealed = LiveTvBackupSecretsAndroid.seal(profile, original) { VpnBackup.sealCache(it, key) }
        assertFalse(sealed.contains("synthetic-provider-secret"))
        assertFalse(sealed.contains("synthetic-indexer-secret"))
        assertEquals(sealed, LiveTvBackupSecretsAndroid.seal(profile, original, sealed) {
            error("Unchanged source settings must reuse their ciphertext")
        })
        assertEquals(original, LiveTvBackupSecretsAndroid.open(profile, sealed) { VpnBackup.openCache(it, key) })
        assertEquals(original, LiveTvBackupSecretsAndroid.open(profile, original.value))
    }

    @Test fun unreadableOrSwappedProtectedPreferencesRetainOnlyTheOriginalCiphertext() {
        val sealed = LiveTvBackupSecretsAndroid.seal(profile, original) { VpnBackup.sealCache(it, key) }
        assertEquals(PrefValue.UnavailableBackupSecret(sealed),
            LiveTvBackupSecretsAndroid.open("usenet_sources_v1_p4", sealed) { VpnBackup.openCache(it, key) })
        assertEquals(PrefValue.UnavailableBackupSecret(sealed),
            LiveTvBackupSecretsAndroid.open(profile, sealed) { error("Synthetic unreadable key") })
        val damaged = sealed.replace("\"iv\":", "\"missingIv\":")
        assertEquals(PrefValue.UnavailableBackupSecret(damaged),
            LiveTvBackupSecretsAndroid.open(profile, damaged) { VpnBackup.openCache(it, key) })
    }

    @Test fun unavailableSecretsDoNotBlockLoadingOrOrdinaryIptvWritesAndCannotBeExported() {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("synthetic-unavailable-backup", Context.MODE_PRIVATE)
        val damaged = "nuvio.backup.local.v1:synthetic-unreadable-ciphertext"
        val playlists = """[{"id":"existing","name":"Local","url":"https://example.test/list.m3u"}]"""
        preferences.edit().clear().putString(profile, damaged).putString("playlists", playlists).commit()
        val phone = object : LiveTvPreferencesStore() {
            init {
                installPersistence(
                    load = { LiveTvPreferencesAndroid.read(preferences) },
                    save = { LiveTvPreferencesAndroid.write(preferences, it) },
                )
            }
        }
        assertEquals("existing", phone.currentPlaylists().single().id)
        phone.recordWatched("synthetic-channel")
        phone.edit { it.remove(profile) }
        assertEquals(damaged, preferences.getString(profile, null))
        assertEquals(PrefValue.UnavailableBackupSecret(damaged), phone.rawData.value[profile])
        assertEquals("existing", phone.currentPlaylists().single().id)
        // Left out of sync instead of stopping it; the other devices' copy repairs this one.
        assertFalse(phone.exportForSync().containsKey(profile))
        assertEquals("UnavailableBackupSecret", phone.rawData.value[profile].toString())
    }

    @Test fun legacyEncryptionFailureKeepsTheAppUsableAndTheOriginalInputQuarantined() {
        checkLegacyFailure(false) { _, _, _ -> error("Synthetic encryption failure") }
    }

    @Test fun legacyCommitFailureRestoresTheOriginalMapBeforeOrdinaryWrites() {
        checkLegacyFailure(true) { name, value, stored ->
            LiveTvBackupSecretsAndroid.seal(name, value, stored) { VpnBackup.sealCache(it, key) }
        }
    }

    private fun checkLegacyFailure(failCommit: Boolean, protection: BackupPreferenceProtection) {
        val context: Context = RuntimeEnvironment.getApplication()
        val originalPreferences = context.getSharedPreferences("synthetic-legacy-backup-$failCommit", Context.MODE_PRIVATE)
        originalPreferences.edit().clear().putString(profile, original.value)
            .putString("playlists", """[{"id":"existing","name":"Local","url":"https://example.test/list.m3u"}]""").commit()
        val preferences: SharedPreferences = if (failCommit) FailingCommitPreferences(originalPreferences) else originalPreferences
        var protectionCalls = 0
        val protect: BackupPreferenceProtection = { name, value, stored ->
            protectionCalls++
            protection(name, value, stored)
        }
        val phone = object : LiveTvPreferencesStore() {
            init {
                installPersistence(
                    load = { LiveTvPreferencesAndroid.read(preferences, protect) },
                    save = { LiveTvPreferencesAndroid.write(preferences, it, protect) },
                )
            }
        }
        val callsAfterLoad = protectionCalls
        assertTrue(callsAfterLoad > 0)
        phone.recordWatched("synthetic-channel")
        assertEquals(callsAfterLoad, protectionCalls, "Ordinary writes must not retry unavailable crypto")
        assertEquals("existing", phone.currentPlaylists().single().id)
        assertEquals(PrefValue.UnavailableBackupSecret(original.value), phone.rawData.value[profile])
        assertEquals(original.value, originalPreferences.getString(profile, null))
        assertFalse(phone.exportForSync().containsKey(profile))
    }

    /** Models commit(false) after SharedPreferences has already replaced its in-memory map. */
    private class FailingCommitPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val target = delegate.edit()
            return object : SharedPreferences.Editor by target {
                override fun clear(): SharedPreferences.Editor { target.clear(); return this }
                override fun commit(): Boolean { target.apply(); return false }
            }
        }
    }
}
