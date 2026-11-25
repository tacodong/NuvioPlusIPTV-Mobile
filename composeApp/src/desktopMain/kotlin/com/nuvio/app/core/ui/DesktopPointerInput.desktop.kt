package com.nuvio.app.core.ui

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.onDesktopMouseBack(onBack: () -> Unit): Modifier = pointerInput(onBack) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.button == PointerButton.Back &&
                event.changes.none { it.isConsumed }
            ) {
                event.changes.forEach { it.consume() }
                onBack()
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.consumeDesktopMouseNavigation(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press &&
                (event.button == PointerButton.Back || event.button == PointerButton.Forward)
            ) {
                event.changes.forEach { it.consume() }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.onDesktopScroll(onScroll: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Scroll) { onScroll() }

private val isMacMouseInput = System.getProperty("os.name", "").contains("mac", ignoreCase = true)

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.onDesktopSecondaryClick(onClick: () -> Unit): Modifier = composed {
    val action = rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Eat the secondary press before clickable / combinedClickable can treat it
                // as a primary activation. macOS also uses Control-click as a secondary click.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press &&
                    (event.button == PointerButton.Secondary ||
                        (isMacMouseInput && event.button == PointerButton.Primary && event.keyboardModifiers.isCtrlPressed)) &&
                    event.changes.none { it.isConsumed }
                ) {
                    event.changes.forEach { it.consume() }
                    action.value()
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.onDesktopHorizontalWheel(scrollBy: (Float) -> Unit): Modifier = composed {
    val action = rememberUpdatedState(scrollBy)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll || event.changes.any { it.isConsumed }) continue
                val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                val horizontal = when {
                    delta.x != 0f && kotlin.math.abs(delta.x) >= kotlin.math.abs(delta.y) -> delta.x
                    event.keyboardModifiers.isShiftPressed -> delta.y
                    else -> 0f
                }
                if (horizontal != 0f) {
                    event.changes.forEach { it.consume() }
                    action.value(horizontal * 48.dp.toPx())
                }
            }
        }
    }
}
