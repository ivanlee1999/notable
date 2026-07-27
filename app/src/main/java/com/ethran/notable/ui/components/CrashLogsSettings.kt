package com.ethran.notable.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.NotableApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class CrashLog(val name: String, val content: String)

/**
 * Debug-tab section that surfaces the local crash files ([NotableApp] writes one per crash) so a
 * user can read and **share** them when asked, instead of them being reachable only over adb.
 * ShipBook is the automatic remote channel; this is the fallback for the cases ShipBook can't cover
 * (network/heap dead, or a crash before ShipBook starts). Plan Phase 8e-1.
 */
@Composable
fun CrashLogsSettings() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Bumping this re-runs the loader (after a Clear).
    var reload by remember { mutableIntStateOf(0) }

    val logs by produceState(initialValue = emptyList<CrashLog>(), reload) {
        value = withContext(Dispatchers.IO) { loadCrashLogs(context) }
    }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Crash logs (${logs.size})",
            style = MaterialTheme.typography.subtitle1
        )
        Spacer(Modifier.height(4.dp))

        if (logs.isEmpty()) {
            Text(
                text = "No crash logs — nothing has crashed since the last clear.",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
            )
            return@Column
        }

        val combined = remember(logs) { combineForExport(logs) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = combined,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                color = MaterialTheme.colors.onSurface
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                clipboard.setText(AnnotatedString(combined))
                Toast.makeText(context, "Copied crash logs", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }

            Button(onClick = { shareText(context, combined) }) { Text("Share") }

            Button(onClick = {
                clearCrashLogs(context)
                reload++
            }) { Text("Clear") }
        }
    }
}

/** Newest-first; small text files, so reading them here is cheap (and this runs off the main thread). */
private fun loadCrashLogs(context: Context): List<CrashLog> {
    val dir = File(context.filesDir, NotableApp.CRASH_DIR)
    val files = dir.listFiles()?.sortedByDescending { it.name } ?: return emptyList()
    return files.map { file ->
        val content = runCatching { file.readText() }.getOrElse { "<unreadable: ${it.message}>" }
        CrashLog(file.name, content)
    }
}

private fun combineForExport(logs: List<CrashLog>): String =
    logs.joinToString("\n\n") { "──── ${it.name} ────\n${it.content}" }

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Notable crash logs")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share crash logs"))
}

private fun clearCrashLogs(context: Context) {
    runCatching {
        File(context.filesDir, NotableApp.CRASH_DIR).listFiles()?.forEach { it.delete() }
    }
}
