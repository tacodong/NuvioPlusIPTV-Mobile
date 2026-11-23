package com.nuvio.app.features.livetv.data

// TODO(desktop): Integrate VPN profiles without restoring Android VPN state.
internal actual fun exportVpnBackup(legacySnapshot: String?): String? = null
internal actual fun restoreVpnBackup(value: String): Boolean = false
internal actual fun observeVpnBackupChanges(onChange: () -> Unit) = Unit
