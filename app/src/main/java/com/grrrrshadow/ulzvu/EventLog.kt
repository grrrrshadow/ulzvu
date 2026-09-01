package com.grrrrshadow.ulzvu

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(val timestampMs: Long, val level: LogLevel, val tag: String, val message: String)

/**
 * Every entry is appended straight into Stažené soubory/Ulzvu/ulzvu_log.txt — a normal file
 * in the device's Downloads, so a crash or a silent recording failure leaves a trail the user
 * can open in any file manager, without having to dig through app-private storage.
 */
object EventLog {
    private const val MAX_ENTRIES = 500
    private const val FILE_NAME = "ulzvu_log.txt"
    private val RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/Ulzvu/"
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val entries = ArrayDeque<LogEntry>()
    private val lock = Any()

    private var appContext: Context? = null
    private var logUri: Uri? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        logUri = try {
            findOrCreateLogUri(context.applicationContext)
        } catch (_: Exception) {
            null
        }
    }

    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message :: ${throwable.javaClass.simpleName}: ${throwable.message}\n${throwable.stackTraceToString()}"
        } else message
        val entry = LogEntry(System.currentTimeMillis(), level, tag, fullMessage)

        synchronized(lock) {
            entries.addLast(entry)
            if (entries.size > MAX_ENTRIES) entries.removeFirst()
            // INFO is routine chatter (BPM ticks, config lines) -- only WARN/ERROR
            // (incidents, failures) are worth persisting to the file on disk.
            if (level != LogLevel.INFO) appendToDownloads(format(entry))
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    fun format(entry: LogEntry): String =
        "${timeFormat.format(Date(entry.timestampMs))} [${entry.level}] ${entry.tag}: ${entry.message}"

    fun readAll(): String {
        val context = appContext ?: return ""
        val uri = logUri ?: return ""
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun clear() {
        val context = appContext
        val uri = logUri
        if (context != null && uri != null) {
            try {
                context.contentResolver.openFileDescriptor(uri, "wt")?.close()
            } catch (_: Exception) {
                // best effort
            }
        }
        synchronized(lock) { entries.clear() }
    }

    private fun findOrCreateLogUri(context: Context): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val args = arrayOf(FILE_NAME, RELATIVE_PATH)

        resolver.query(collection, projection, selection, args, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
        }
        return resolver.insert(collection, values)
    }

    private fun appendToDownloads(line: String) {
        val context = appContext ?: return
        val uri = logUri ?: return
        try {
            context.contentResolver.openFileDescriptor(uri, "wa")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    out.write((line + "\n").toByteArray())
                }
            }
        } catch (_: Exception) {
            // best effort -- the in-memory ring buffer still holds this entry for the session
        }
    }
}
