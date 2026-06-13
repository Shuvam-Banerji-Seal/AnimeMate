/*
 * AnimeRec - Anime Recomendación App
 * Copyright (C) 2025 Shuvam Banerji Seal
 *
 * Developed by: Shuvam Banerji Seal
 * GitHub: https://github.com/technicallittlemaster
 *
 * This file is part of AnimeRec.
 * Licensed under the MIT License.
 */
package com.animerec.app.ui.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.animerec.app.util.ErrorLogManager

/**
 * Launches the MAL OAuth authorization URL in the user's system browser.
 *
 * **Why the system browser, not in-app WebView / Custom Tabs?**
 *
 * Google, Apple, Facebook, and X (Twitter) all block OAuth flows that
 * originate from an embedded `WebView` inside the host app — this is a
 * security feature designed to prevent credential phishing. Custom
 * Tabs (which share cookies with the system browser) are also flaky
 * for some providers because the user-agent and navigation context
 * differ from a "real" browser tab. Shipping the user out to the
 * system browser guarantees a clean OAuth dance and lets the user
 * benefit from their browser's existing login state.
 *
 * **Robust launch path.**
 *
 * Earlier iterations of this class added `FLAG_ACTIVITY_REQUIRE_NON_BROWSER`,
 * which has the surprising semantics of *"do not deliver this Intent
 * to a browser app"*. The only apps that can handle
 * `https://myanimelist.net/...` *are* browsers, so that flag filtered
 * out every candidate and `startActivity` threw
 * `ActivityNotFoundException` — surfacing as "no browser available"
 * to the user, even on devices that had Chrome installed.
 *
 * v1.1.4 removes that flag and adds a 3-stage fallback chain so we
 * launch successfully in every reasonable environment:
 *
 *  1. **`Intent.ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK`** — the
 *     standard, recommended path. Works on every device with a
 *     browser.
 *  2. **`Intent.createChooser(...)`** — forces the system to show
 *     the app picker. Useful when the system can't decide on a
 *     default (e.g. user disabled defaults) or when a malicious
 *     app has registered itself as a higher-priority handler.
 *  3. **`Intent.ACTION_VIEW` without `NEW_TASK`** — last resort for
 *     when the calling context is somehow not Activity-attachable.
 *
 * After all 3 stages fail, the launcher surfaces a structured
 * `AuthCallbackBus` error so the login fragment can display a
 * helpful "please install a browser" message instead of leaving the
 * user staring at a frozen login screen.
 */
object OAuthLauncher {

    private const val TAG = "OAuthLauncher"

    /**
     * Open [authUrl] in the system browser. Tries 3 launch strategies
     * in order. Returns `true` if any succeeded; `false` if all 3
     * failed and the user has been notified.
     */
    fun launch(context: Context, authUrl: String): Boolean {
        val uri = Uri.parse(authUrl)

        // Pre-flight: check if any browser is actually installed. This
        // catches the "vanilla AOSP emulator with no Google Apps" case
        // up front and lets us give a precise error message instead of
        // going through 3 doomed startActivity attempts.
        if (!hasAnyBrowser(context)) {
            return reportNoBrowser(context)
        }

        // Strategy 1: ACTION_VIEW + NEW_TASK (the recommended path).
        val strategy1 = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(context, strategy1)) {
            Log.d(TAG, "OAuth URL launched via strategy 1 (ACTION_VIEW + NEW_TASK)")
            return true
        }

        // Strategy 2: createChooser — force the system picker UI.
        // Useful when the system can't pick a default (some custom
        // ROMs and AOSP emulators land here) or when a higher-
        // priority handler has hijacked the URL.
        val strategy2 = Intent.createChooser(
            Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            "Open login page with"
        )
        if (tryStartActivity(context, strategy2)) {
            Log.d(TAG, "OAuth URL launched via strategy 2 (createChooser)")
            return true
        }

        // Strategy 3: ACTION_VIEW without NEW_TASK. This works when
        // the calling context is somehow attached to an Activity
        // already (e.g. the launcher was called from a Fragment) and
        // the system refuses the NEW_TASK form.
        val strategy3 = Intent(Intent.ACTION_VIEW, uri)
        if (tryStartActivity(context, strategy3)) {
            Log.d(TAG, "OAuth URL launched via strategy 3 (ACTION_VIEW only)")
            return true
        }

        // All 3 strategies failed. The pre-flight said a browser was
        // installed, so something else is going on (broken intent
        // filters, disabled default apps, etc.).
        return reportNoBrowser(context)
    }

    /**
     * Returns `true` if the device has at least one app that resolves
     * the `https://` scheme. Uses [PackageManager.MATCH_DEFAULT_ONLY]
     * which honours the user's default-app preferences; combined with
     * an `https://` URL this effectively asks *"is there a browser
     * the system thinks I should use?"*.
     */
    private fun hasAnyBrowser(context: Context): Boolean {
        val testIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
        val resolvers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                testIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(
                testIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
        }
        return resolvers.isNotEmpty()
    }

    /**
     * Tries to start [intent] and swallows the
     * `ActivityNotFoundException`. Returns `true` on success.
     *
     * Using a wrapper rather than a try/catch at every call site
     * keeps the launch function readable and ensures the
     * error-handling contract is identical across the 3 strategies.
     */
    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "startActivity threw ActivityNotFoundException for $intent: ${e.message}")
            false
        } catch (e: SecurityException) {
            // Some OEM ROMs throw SecurityException when an intent
            // tries to leave the calling app. Treat the same as
            // ActivityNotFoundException — try the next strategy.
            Log.w(TAG, "startActivity threw SecurityException for $intent: ${e.message}")
            false
        } catch (e: Exception) {
            // Any other failure (e.g. TransactionTooLargeException on
            // some bug-ridden launchers). Log and continue.
            Log.w(TAG, "startActivity failed for $intent: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /**
     * All launch strategies failed. Surface a structured error to the
     * UI via [AuthCallbackBus] so the user sees a helpful message.
     */
    private fun reportNoBrowser(context: Context): Boolean {
        val message = "No browser is available to complete the sign-in. " +
            "Please install Chrome, Firefox, or any other browser and try again."
        Log.e(TAG, message)
        ErrorLogManager.logEvent(TAG, "ERROR", message)
        AuthCallbackBus.postError("no_browser", message)
        return false
    }
}
