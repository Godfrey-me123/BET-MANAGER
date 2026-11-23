package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class AppLogLevel(val label: String, val emoji: String) {
    DEBUG("DEBUG", "·"),
    INFO("INFO", "ℹ"),
    SUCCESS("OK", "✓"),
    WARN("WARN", "⚠"),
    ERROR("ERROR", "✗")
}

/**
 * One diagnostic line for the App Logs panel (success + errors from automation, gestures, strategy).
 * Designed to be human-readable and easy to copy for support/debug.
 */
data class AppLogEntry(
    val id: Long = nextId(),
    val timestampMs: Long = System.currentTimeMillis(),
    val level: AppLogLevel = AppLogLevel.INFO,
    val tag: String = "App",
    val message: String,
    val details: String? = null,
    val actionId: String? = null,
    val coordinates: Pair<Float, Float>? = null
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(timestampMs))
    }

    fun formattedDateTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(timestampMs))
    }

    /** Single-line summary for list UI. */
    fun formattedLine(): String {
        val coord = coordinates?.let { " @(${it.first.toInt()},${it.second.toInt()})" } ?: ""
        val det = details?.takeIf { it.isNotBlank() }?.let { " | $it" } ?: ""
        return "${formattedTime()} [${level.label}] $tag: $message$coord$det"
    }

    /** Multi-line block for Copy All export. */
    fun formattedExportBlock(): String {
        val sb = StringBuilder()
        sb.append(formattedDateTime())
            .append(" [").append(level.label).append("] ")
            .append(tag)
        if (!actionId.isNullOrBlank()) sb.append(" action=").append(actionId)
        sb.append('\n').append("  ").append(message)
        coordinates?.let { sb.append("\n  coords=(").append(it.first).append(", ").append(it.second).append(')') }
        details?.takeIf { it.isNotBlank() }?.let { sb.append("\n  details: ").append(it) }
        return sb.toString()
    }

    companion object {
        private val idSeq = AtomicLong(1L)
        private fun nextId(): Long = idSeq.getAndIncrement()
    }
}
