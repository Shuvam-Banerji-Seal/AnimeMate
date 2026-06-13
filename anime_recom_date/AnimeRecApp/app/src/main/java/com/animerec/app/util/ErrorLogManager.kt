/*
 * AnimeRec - Anime Recommendation App
 * Copyright (C) 2025 Shuvam Banerji Seal
 *
 * Developed by: Shuvam Banerji Seal
 * GitHub: https://github.com/technicallittlemaster
 *
 * This file is part of AnimeRec.
 * Licensed under the MIT License.
 */
package com.animerec.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages collection, automatic persistence, and emailing of application logs.
 *
 * **Automatic crash logs** — call [installCrashHandler] once in Application.onCreate().
 * Every uncaught exception is written to `files/logs/crash_<timestamp>.txt`.
 *
 * **On-demand error log** — call [sendErrorLogs] from the Profile page to email
 * the developer a combined report (device info + logcat + saved preferences +
 * any crash files that haven't been sent yet).
 *
 * Log directory: `context.filesDir/logs/`
 */
object ErrorLogManager {

    private const val TAG = "ErrorLogManager"
    const val DEVELOPER_EMAIL = "sbs22ms076@iiserkol.ac.in"
    private const val LOG_FILE_NAME = "animerec_error_log.txt"
    private const val MAX_LOG_LINES = 500
    private const val LOG_DIR_NAME = "logs"
    private const val MAX_CRASH_FILES = 20  // keep the latest N crash files

    // ──────────────────────────────────────────────
    //  0. Structured logging helpers
    // ──────────────────────────────────────────────

    /**
     * Log a structured event with tag + category for easier filtering.
     * Output: `[category] message`
     */
    fun logEvent(tag: String, category: String, message: String) {
        Log.d(tag, "[$category] $message")
    }

    /**
     * Time a suspending block and log the duration.
     * Returns the block's result.
     *
     * Usage:
     * ```
     * val result = ErrorLogManager.logTimed(TAG, "API", "fetchAnimeRankings") {
     *     apiClient.service.getAnimeRankings(...)
     * }
     * ```
     */
    inline fun <T> logTimed(tag: String, category: String, operation: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            val elapsed = System.currentTimeMillis() - start
            Log.d(tag, "[$category] $operation completed in ${elapsed}ms")
            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            Log.e(tag, "[$category] $operation failed after ${elapsed}ms", e)
            throw e
        }
    }

    // ──────────────────────────────────────────────
    //  1. Automatic crash handler
    // ──────────────────────────────────────────────

    /**
     * Install a global UncaughtExceptionHandler that writes the stack trace
     * to a file before delegating to the default handler (which kills the process).
     *
     * Call this **once** in Application.onCreate().
     */
    fun installCrashHandler(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(appContext, thread, throwable)
            } catch (_: Exception) {
                // best-effort; don't let the handler itself crash
            }
            // Delegate to the system default so the process terminates normally
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.d(TAG, "Crash handler installed — logs at ${getLogDir(appContext).absolutePath}")
    }

    /**
     * Return the directory that contains all log / crash files.
     * Users / testers can find it at:
     *   `/data/data/com.animerec.app/files/logs/`
     * (accessible via Device File Explorer in Android Studio, or `adb shell run-as com.animerec.app ls files/logs`).
     */
    fun getLogDir(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Write a single crash file named `crash_<timestamp>.txt`.
     */
    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val dir = getLogDir(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_$timestamp.txt")

        FileWriter(file, false).use { w ->
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            w.append("=== AnimeRec Crash Report ===\n")
            w.append("Time   : ${df.format(Date())}\n")
            w.append("Thread : ${thread.name} (id=${thread.id})\n")
            w.append("Device : ${Build.MANUFACTURER} ${Build.MODEL}\n")
            w.append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            try {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                w.append("App    : ${pi.versionName} (${pi.longVersionCode})\n")
            } catch (_: Exception) { /* ignore */ }
            w.append("\n── Stack Trace ──\n")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            w.append(sw.toString())
            w.append("\n=== END ===\n")
        }

        // Prune old files
        pruneOldCrashFiles(dir)
    }

    private fun pruneOldCrashFiles(dir: File) {
        val crashFiles = dir.listFiles { f -> f.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() } ?: return
        if (crashFiles.size > MAX_CRASH_FILES) {
            crashFiles.drop(MAX_CRASH_FILES).forEach { it.delete() }
        }
    }

    // ──────────────────────────────────────────────
    //  2. On-demand "Send Error Logs" (email)
    // ──────────────────────────────────────────────

    /**
     * Collect logs and launch an email intent with the log file attached.
     */
    fun sendErrorLogs(context: Context) {
        try {
            val logFile = collectLogs(context)
            launchEmailIntent(context, logFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send error logs", e)
        }
    }

    /**
     * Collect device info + logcat + crash files + preferences into one report.
     */
    private fun collectLogs(context: Context): File {
        val logFile = File(context.cacheDir, LOG_FILE_NAME)
        FileWriter(logFile, false).use { writer ->
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            writer.append("=== AnimeRec Error Log ===\n")
            writer.append("Generated: ${dateFormat.format(Date())}\n\n")

            // ── Device Info ──
            writer.append("── Device Info ──\n")
            writer.append("Manufacturer: ${Build.MANUFACTURER}\n")
            writer.append("Model: ${Build.MODEL}\n")
            writer.append("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            writer.append("Device: ${Build.DEVICE}\n")
            writer.append("Product: ${Build.PRODUCT}\n")
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                writer.append("App Version: ${packageInfo.versionName} (${packageInfo.longVersionCode})\n")
            } catch (e: Exception) {
                writer.append("App Version: unknown\n")
            }
            writer.append("\n")

            // ── Saved Crash Files ──
            writer.append("── Saved Crash Logs ──\n")
            val crashDir = getLogDir(context)
            val crashFiles = crashDir.listFiles { f -> f.name.startsWith("crash_") }
                ?.sortedByDescending { it.lastModified() }
                ?.take(5) // include the 5 most recent crashes
            if (crashFiles.isNullOrEmpty()) {
                writer.append("(no crash logs found)\n")
            } else {
                for (cf in crashFiles) {
                    writer.append("\n── ${cf.name} ──\n")
                    writer.append(cf.readText())
                    writer.append("\n")
                }
            }
            writer.append("\n")

            // ── Logcat ──
            writer.append("── Recent Logcat ──\n")
            try {
                val pid = android.os.Process.myPid()
                val process = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "-d",
                    "-v", "time",
                    "--pid=$pid",
                    "*:W"
                ))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                var lineCount = 0
                while (reader.readLine().also { line = it } != null && lineCount < MAX_LOG_LINES) {
                    writer.append(line).append('\n')
                    lineCount++
                }
                reader.close()
                process.waitFor()
            } catch (e: Exception) {
                writer.append("Failed to capture logcat: ${e.message}\n")
            }

            // ── SharedPreferences snapshot (redacted) ──
            // S5: Previously the user's genre/content preferences and minimum
            // rating were appended verbatim to the emailed error log. This
            // is a PII leak — anyone intercepting the email could see the
            // user's "not_interested" list, favourite genres, and content
            // preferences. Now we only include key existence + a SHA-256
            // fingerprint so developers can correlate "user X has
            // preferences" with prior reports without seeing the values.
            writer.append(buildPreferencesSnapshot(context))

            writer.append("\n=== END OF LOG ===\n")
        }

        return logFile
    }

    /**
     * Launch an email intent with the log file as attachment.
     */
    private fun launchEmailIntent(context: Context, logFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val logUri: Uri = FileProvider.getUriForFile(context, authority, logFile)

        val emailIntent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "AnimeRec Error Log - ${Build.MODEL}")
            putExtra(Intent.EXTRA_TEXT, buildString {
                append("Hi,\n\n")
                append("Please find the error log attached.\n\n")
                append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("Android: ${Build.VERSION.RELEASE}\n\n")
                append("Description of the issue:\n")
                append("[Please describe what happened here]\n")
            })
            putExtra(Intent.EXTRA_STREAM, logUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(emailIntent, "Send Error Log via...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * S5: Build a redacted SharedPreferences snapshot. Only key existence
     * and a SHA-256 fingerprint of the values are included — no raw
     * preferences leak in the error log email. Exposed (internal) for
     * tests.
     */
    internal fun buildPreferencesSnapshot(context: Context): String {
        return buildString {
            append("\n── Preferences Snapshot (redacted) ──\n")
            try {
                val prefs = context.getSharedPreferences("anime_repo_prefs", Context.MODE_PRIVATE)
                val keys = listOf("profile_complete", "content_preferences", "genre_preferences", "minimum_rating")
                for (key in keys) {
                    val has = prefs.contains(key)
                    append("$key: ${if (has) "set" else "unset"}\n")
                }
                val fingerprintSource = (prefs.getString("content_preferences", "")
                    ?: "") + "|" + (prefs.getString("genre_preferences", "")
                    ?: "")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(fingerprintSource.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                append("prefs-fingerprint-sha256: $hash\n")
            } catch (e: Exception) {
                append("Failed to read preferences: ${e.message}\n")
            }
        }
    }
}
