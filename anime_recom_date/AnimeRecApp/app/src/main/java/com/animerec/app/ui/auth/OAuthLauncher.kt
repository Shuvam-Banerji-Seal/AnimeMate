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
import android.net.Uri
import android.util.Log
import com.animerec.app.util.ErrorLogManager

/**
 * Launches the MAL OAuth authorization URL in the user's default
 * browser. That's the entire job of this class.
 *
 * **Why so simple?** v1.1.1 had exactly this logic and it worked
 * for every user. v1.1.4 added a "pre-flight" check that called
 * `PackageManager.queryIntentActivities()` to confirm a browser
 * was installed before launching. On API 30+ (Android 11) that
 * check returns **empty** even when the user has Chrome installed,
 * because of the package-visibility rules introduced in
 * Android 11 — apps can only see other apps that:
 *   1. Are signed with the same key (system apps), OR
 *   2. Have a `<queries>` element in their manifest
 *   3. Match one of the OS's "implicit query" exemptions
 *
 * Without a `<queries>` entry for browsers in our manifest, the
 * pre-flight check always failed on Android 11+, which is why
 * v1.1.4 reported "no browser available" on devices that clearly
 * had browsers.
 *
 * The fix is two-part:
 *   1. Drop the pre-flight check entirely. If the user's device
 *      has no browser (vanilla AOSP emulator), the
 *      `startActivity` call will throw `ActivityNotFoundException`,
 *      which we catch and surface as a friendly error.
 *   2. Add `<queries>` to AndroidManifest for the `https` scheme,
 *      so any future code that needs to query browsers can see
 *      them. (See AndroidManifest.xml.)
 *
 * The result: one `Intent.ACTION_VIEW` call, one try/catch, done.
 * Same simplicity as v1.1.1, but with proper error handling.
 *
 * **Why the system browser, not in-app WebView / Custom Tabs?**
 * Google, Apple, Facebook, and X (Twitter) all block OAuth flows
 * that originate from an embedded WebView. The system browser is
 * the only universally-approved surface.
 */
object OAuthLauncher {

    private const val TAG = "OAuthLauncher"

    /**
     * Open [authUrl] in the user's default browser.
     *
     * @return `true` if the launch was accepted by the system
     *   (i.e. some activity started). `false` if no activity
     *   could handle the intent (no browser installed).
     */
    fun launch(context: Context, authUrl: String): Boolean {
        val uri = Uri.parse(authUrl)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            // NEW_TASK: required so we can launch from any context
            // (Fragment, Application, etc.) and so the OS routes
            // the deep-link redirect to a stable task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Log.d(TAG, "OAuth URL launched: $authUrl")
            true
        } catch (e: ActivityNotFoundException) {
            // No browser (or any activity that handles https://).
            // Surface a clear error so the user knows what to do.
            val message = "No browser is available to complete the sign-in. " +
                "Please install Chrome, Firefox, or any other browser and try again."
            Log.e(TAG, "ActivityNotFoundException for $authUrl", e)
            ErrorLogManager.logEvent(TAG, "ERROR", message)
            AuthCallbackBus.postError("no_browser", message)
            false
        } catch (e: SecurityException) {
            // Some OEM ROMs throw SecurityException when an intent
            // tries to leave the app. Less common; treat the same
            // way and surface a clear error.
            val message = "System blocked the browser launch. " +
                "Please check your app permissions and try again."
            Log.e(TAG, "SecurityException for $authUrl", e)
            ErrorLogManager.logEvent(TAG, "ERROR", message)
            AuthCallbackBus.postError("security_error", message)
            false
        }
    }
}
