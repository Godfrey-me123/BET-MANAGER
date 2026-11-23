package com.example.service

import android.util.Log
import com.example.data.model.ActionExecutionState
import com.example.data.model.ActivityLogEntry
import com.example.data.model.AppLogEntry
import com.example.data.model.AppLogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central in-memory log ring for the whole app.
 * Captures automation success/failure, gesture dispatch, strategy, and accessibility events
 * so the user can review and Copy All from the Logs screen.
 */
object AppLogManager {
    private const val TAG = "AppLogManager"
    private const val MAX_ENTRIES = 500

    private val lock = Any()
    private val buffer = CopyOnWriteArrayList<AppLogEntry>()
    private val _logs = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val logs: StateFlow<List<AppLogEntry>> = _logs.asStateFlow()

    fun d(tag: String, message: String, details: String? = null, actionId: String? = null) =
        append(AppLogLevel.DEBUG, tag, message, details, actionId)

    fun i(tag: String, message: String, details: String? = null, actionId: String? = null) =
        append(AppLogLevel.INFO, tag, message, details, actionId)

    fun success(
        tag: String,
        message: String,
        details: String? = null,
        actionId: String? = null,
        coordinates: Pair<Float, Float>? = null
    ) = append(AppLogLevel.SUCCESS, tag, message, details, actionId, coordinates)

    fun w(tag: String, message: String, details: String? = null, actionId: String? = null) =
        append(AppLogLevel.WARN, tag, message, details, actionId)

    fun e(
        tag: String,
        message: String,
        details: String? = null,
        actionId: String? = null,
        coordinates: Pair<Float, Float>? = null,
        throwable: Throwable? = null
    ) {
        val extra = buildString {
            if (!details.isNullOrBlank()) append(details)
            if (throwable != null) {
                if (isNotEmpty()) append(" | ")
                append(throwable.javaClass.simpleName).append(": ").append(throwable.message ?: "no message")
            }
        }.ifBlank { null }
        append(AppLogLevel.ERROR, tag, message, extra, actionId, coordinates)
        if (throwable != null) {
            Log.e(TAG, "[$tag] $message", throwable)
        } else {
            Log.e(TAG, "[$tag] $message ${extra ?: ""}")
        }
    }

    /**
     * Bridge from AutomationEngine ActivityLogEntry into the unified buffer.
     */
    fun fromActivityLog(entry: ActivityLogEntry) {
        val level = when {
            entry.verificationResult == true || entry.state == ActionExecutionState.COMPLETED -> AppLogLevel.SUCCESS
            entry.state == ActionExecutionState.FAILED || entry.gestureResult == false ||
                entry.verificationResult == false || !entry.errorDetails.isNullOrBlank() -> AppLogLevel.ERROR
            entry.gestureResult == true -> AppLogLevel.INFO
            else -> AppLogLevel.INFO
        }
        val msg = buildString {
            append(entry.target)
            entry.eventObserved?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
        }
        append(
            level = level,
            tag = "Automation",
            message = msg,
            details = entry.errorDetails,
            actionId = entry.actionId,
            coordinates = entry.coordinates
        )
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            publishLocked()
        }
        i("AppLog", "Logs cleared")
    }

    /** Full export text for clipboard (newest last for chronological reading). */
    fun exportAllText(filter: AppLogLevel? = null): String {
        val snapshot = _logs.value
        val filtered = if (filter == null) snapshot else snapshot.filter { it.level == filter }
        if (filtered.isEmpty()) return "(no log entries)"
        val header = "=== Predict Manager App Logs (${filtered.size} entries) ===\n"
        return header + filtered.joinToString("\n\n") { it.formattedExportBlock() }
    }

    fun exportLines(filter: AppLogLevel? = null): String {
        val snapshot = _logs.value
        val filtered = if (filter == null) snapshot else snapshot.filter { it.level == filter }
        if (filtered.isEmpty()) return "(no log entries)"
        return filtered.joinToString("\n") { it.formattedLine() }
    }

    private fun append(
        level: AppLogLevel,
        tag: String,
        message: String,
        details: String? = null,
        actionId: String? = null,
        coordinates: Pair<Float, Float>? = null
    ) {
        val entry = AppLogEntry(
            level = level,
            tag = tag.take(32),
            message = message.take(500),
            details = details?.take(800),
            actionId = actionId,
            coordinates = coordinates
        )
        synchronized(lock) {
            buffer.add(entry)
            while (buffer.size > MAX_ENTRIES) {
                buffer.removeAt(0)
            }
            publishLocked()
        }
        when (level) {
            AppLogLevel.DEBUG -> Log.d(TAG, entry.formattedLine())
            AppLogLevel.INFO, AppLogLevel.SUCCESS -> Log.i(TAG, entry.formattedLine())
            AppLogLevel.WARN -> Log.w(TAG, entry.formattedLine())
            AppLogLevel.ERROR -> { /* already logged in e() when throwable present; still log here for consistency */ }
        }
    }

    private fun publishLocked() {
        _logs.value = buffer.toList()
    }
}
