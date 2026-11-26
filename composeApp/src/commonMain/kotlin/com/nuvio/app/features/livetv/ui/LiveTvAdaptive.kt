package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio

// The same tablet breakpoint as the app shell. Panels can take a separate content-width
// decision after the rail and safe insets have been taken out of the available space.
internal val LiveTvWideWidth = 768.dp

/**
 * The wide Live TV and Sports layouts (desktop-style guide, card grids, centred sheets) are for
 * the desktop app. Phones and tablets keep their own layouts; set this to true to give tablets
 * (iPad, Android tablets) the wide layouts too, at the same width.
 */
internal const val LiveTvWideLayoutsOnTablets = false

@Composable
internal fun isWideLiveTvWindow(): Boolean =
    (com.nuvio.app.isDesktop || LiveTvWideLayoutsOnTablets) &&
        LocalWindowInfo.current.containerDpSize.width >= LiveTvWideWidth

@Composable
internal fun MutableInteractionSource.isHoveredOrFocused(): Boolean {
    val hovered by collectIsHoveredAsState()
    val focused by collectIsFocusedAsState()
    return hovered || focused
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveTvAdaptiveSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    fullHeight: Boolean = false,
    scrollContent: Boolean = false,
    wide: Boolean = isWideLiveTvWindow(),
    wideMaxWidth: Dp = 520.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (wide) {
        val maxHeight = (LocalWindowInfo.current.containerDpSize.height - 96.dp).coerceIn(240.dp, 740.dp)
        val tokens = MaterialTheme.nuvio
        Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.matchParentSize().background(tokens.colors.overlayScrim)
                    .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } })
                Surface(
                    modifier = Modifier.padding(16.dp).widthIn(max = wideMaxWidth).fillMaxWidth(),
                    shape = tokens.shapes.dialog,
                    color = tokens.colors.surfaceDialog.copy(alpha = 1f),
                    contentColor = tokens.colors.textPrimary,
                    border = BorderStroke(tokens.borders.thin, tokens.colors.borderDefault),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(max = maxHeight)
                            .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                        content = content,
                    )
                }
            }
        }
    } else {
        NuvioModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, fullHeight = fullHeight, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
internal suspend fun dismissLiveTvAdaptiveSheet(sheetState: SheetState, wide: Boolean, onDismiss: () -> Unit) {
    if (wide) onDismiss() else dismissNuvioBottomSheet(sheetState, onDismiss)
}
