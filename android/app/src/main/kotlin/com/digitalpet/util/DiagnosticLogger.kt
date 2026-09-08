package com.digitalpet.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight diagnostic logger for the Digital Pet application.
 *
 * [DiagnosticLogger] captures structured log entries with timestamps and tags,
 * writing them to both Android's logcat and an in-memory ring buffer. The
 * buffer can be dumped for debugging or crash reports.
 *
 * Thread-safe: all operations use a lock-free [ConcurrentLinkedDeque].
 */
@Singleton
class DiagnosticLogger @Inject constructor() {

    companion object {
        private const val MAX_ENTRIES = 500
        private val DATE_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    /**
     * A single diagnostic log entry.
     *
     * @property timestamp formatted wall-clock time.
     * @property tag       source component name.
     * @property message   log message body.
     */
    data class LogEntry(
        val timestamp: String,
        val tag: String,
        val message: String
    ) {
        override fun toString(): String = "[$timestamp] $tag: $message"
    }

    private val entries = ConcurrentLinkedDeque<LogEntry>()

    /**
     * Record a diagnostic message.
     *
     * The message is simultaneously written to logcat (at INFO level) and
     * appended to the in-memory ring buffer.
     *
     * @param tag     source component identifier (e.g. "LlmManager").
     * @param message the log message.
     */
    fun log(tag: String, message: String) {
        val entry = LogEntry(
            timestamp = DATE_FORMAT.format(Date()),
            tag = tag,
            message = message
        )
        Log.i(tag, message)

        entries.addLast(entry)

        // Trim oldest entries when the buffer exceeds capacity.
        while (entries.size > MAX_ENTRIES) {
            entries.pollFirst()
        }
    }

    /**
     * Return the full log buffer as a single newline-delimited string.
     */
    fun dump(): String = entries.joinToString(separator = "\n")

    /**
     * Clear all buffered log entries.
     */
    fun clear() {
        entries.clear()
    }
}
