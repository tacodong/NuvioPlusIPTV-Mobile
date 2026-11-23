package com.nuvio.app.features.livetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File

@Composable
internal actual fun rememberLiveTvBackupImporter(onResult: (Result<String?>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    return {
        scope.launch {
            callback.value(runCatching {
                val file = pickBackupFile(FileDialog.LOAD) ?: return@runCatching null
                withContext(Dispatchers.IO) {
                    val bytes = file.inputStream().use { it.readNBytes(MAX_BACKUP_FILE_BYTES + 1) }
                    require(bytes.size <= MAX_BACKUP_FILE_BYTES) { "Backup file is too large" }
                    bytes.decodeToString()
                }
            })
        }
    }
}

@Composable
internal actual fun rememberLiveTvBackupExporter(text: () -> String, onResult: (Result<Boolean>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val contents = rememberUpdatedState(text)
    val callback = rememberUpdatedState(onResult)
    return {
        scope.launch {
            callback.value(runCatching {
                val file = pickBackupFile(FileDialog.SAVE) ?: return@runCatching false
                val data = contents.value()
                withContext(Dispatchers.IO) { file.writeText(data) }
                true
            })
        }
    }
}

private suspend fun pickBackupFile(mode: Int): File? = withContext(Dispatchers.Main) {
    if (GraphicsEnvironment.isHeadless()) throw BackupFilePickerUnavailable()
    val parent = Frame.getFrames().firstOrNull { it.isActive }
    val title = if (mode == FileDialog.LOAD) "Import Live TV backup" else "Export Live TV backup"
    val dialog = FileDialog(parent, title, mode)
    try {
        if (mode == FileDialog.SAVE) dialog.file = "Nuvio Live TV backup.json"
        dialog.isVisible = true
        dialog.files.firstOrNull()
    } finally {
        dialog.dispose()
    }
}
