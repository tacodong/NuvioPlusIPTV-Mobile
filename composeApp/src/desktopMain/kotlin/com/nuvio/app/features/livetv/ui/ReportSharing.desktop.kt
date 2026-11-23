package com.nuvio.app.features.livetv.ui

import androidx.compose.runtime.Composable

// Live TV targets Android; on Desktop the report is copied instead of shared.
// TODO(desktop): Integrate a native share sheet for diagnostic reports.
@Composable
internal actual fun rememberReportSharer(): ((title: String, text: String) -> Unit)? = null
