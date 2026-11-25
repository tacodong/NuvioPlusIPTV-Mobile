package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

internal actual fun Modifier.onDesktopMouseBack(onBack: () -> Unit): Modifier = this
internal actual fun Modifier.consumeDesktopMouseNavigation(): Modifier = this
internal actual fun Modifier.onDesktopScroll(onScroll: () -> Unit): Modifier = this

internal actual fun Modifier.onDesktopSecondaryClick(onClick: () -> Unit): Modifier = this

internal actual fun Modifier.onDesktopHorizontalWheel(scrollBy: (Float) -> Unit): Modifier = this
