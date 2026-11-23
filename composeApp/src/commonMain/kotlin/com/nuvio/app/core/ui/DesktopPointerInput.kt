package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

internal expect fun Modifier.onDesktopMouseBack(onBack: () -> Unit): Modifier
internal expect fun Modifier.consumeDesktopMouseNavigation(): Modifier
internal expect fun Modifier.onDesktopScroll(onScroll: () -> Unit): Modifier
