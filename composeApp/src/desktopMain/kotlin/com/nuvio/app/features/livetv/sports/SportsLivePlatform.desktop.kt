package com.nuvio.app.features.livetv.sports

// TODO(desktop): Integrate persistent sports notifications and notification settings.
internal actual object SportsLivePlatform {
    actual val supported: Boolean = false
    actual fun show(title: String, text: String, openUrl: String?): Boolean = false
    actual fun stop() {}
    actual fun openNotificationSettings() {}
}
