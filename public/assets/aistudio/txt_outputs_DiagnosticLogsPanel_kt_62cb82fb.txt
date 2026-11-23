package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppLogEntry
import com.example.data.model.AppLogLevel
import com.example.service.AppLogManager

/**
 * Copyable diagnostic log panel: success + errors from automation, gestures, strategy.
 * Hosted inside the Logs / Crash History tab as the "App Logs" section.
 */
@Composable
fun DiagnosticLogsPanel(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allLogs by AppLogManager.logs.collectAsState()
    var levelFilter by remember { mutableStateOf<AppLogLevel?>(null) }
    val listState = rememberLazyListState()

    val filtered = remember(allLogs, levelFilter) {
        if (levelFilter == null) allLogs.asReversed()
        else allLogs.filter { it.level == levelFilter }.asReversed()
    }

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "APP LOGS (${filtered.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = {
                    val text = AppLogManager.exportAllText(levelFilter)
                    copyToClipboard(context, text, "App logs copied (${filtered.size} lines)")
                },
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy All", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { AppLogManager.clear() },
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear", fontSize = 12.sp)
            }
        }

        // Level filters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = levelFilter == null,
                onClick = { levelFilter = null },
                label = { Text("All", fontSize = 11.sp) }
            )
            listOf(
                AppLogLevel.SUCCESS,
                AppLogLevel.ERROR,
                AppLogLevel.WARN,
                AppLogLevel.INFO
            ).forEach { lvl ->
                FilterChip(
                    selected = levelFilter == lvl,
                    onClick = { levelFilter = if (levelFilter == lvl) null else lvl },
                    label = { Text(lvl.label, fontSize = 11.sp) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No diagnostic logs yet.\nStart automation or place a bet to see success and error events here.\nUse Copy All to share logs for debugging.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.id }) { entry ->
                    LogRow(
                        entry = entry,
                        onCopyOne = {
                            copyToClipboard(context, entry.formattedExportBlock(), "Log line copied")
                        }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LogRow(
    entry: AppLogEntry,
    onCopyOne: () -> Unit
) {
    val accent = when (entry.level) {
        AppLogLevel.SUCCESS -> Color(0xFF16A34A)
        AppLogLevel.ERROR -> Color(0xFFDC2626)
        AppLogLevel.WARN -> Color(0xFFD97706)
        AppLogLevel.INFO -> Color(0xFF2563EB)
        AppLogLevel.DEBUG -> Color(0xFF64748B)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.08f)
        ),
        onClick = onCopyOne
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(accent, CircleShape)
                    .align(Alignment.CenterVertically)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.formattedTime(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = entry.level.label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = accent
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = entry.tag,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                entry.details?.takeIf { it.isNotBlank() }?.let { det ->
                    Text(
                        text = det,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp
                    )
                }
                entry.coordinates?.let { (x, y) ->
                    Text(
                        text = "coords=(${x.toInt()}, ${y.toInt()})",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy line",
                modifier = Modifier
                    .size(18.dp)
                    .padding(top = 2.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String, toastMsg: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (cm != null) {
        cm.setPrimaryClip(ClipData.newPlainText("app_logs", text))
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
    }
}
