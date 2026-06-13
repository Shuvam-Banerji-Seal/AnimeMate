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
 * Launches the MAL OAuth authorization URL in the user's system browser.
 *
 * **Why the system browser, not in-app WebView / Custom Tabs?**
 *
 * Google, Apple, Facebook, and X (Twitter) all block OAuth flows that
 * originate from an embedded `WebView` inside the host app — this is a
 * security feature designed to prevent credential phishing. Even
 * Chrome Custom Tabs (which share cookies with the system browser)
 * sometimes break the third-party provider's OAuth handshake because
 * the user-agent and navigation context differ from a "real" browser
 * tab. Shipping the user out to the system browser guarantees a clean
 * OAuth dance and lets the user benefit from their browser's existing
 * login state (e.g. they may already be signed in to Google in
 * Chrome).
 *
 * The flow is therefore:
 *
 *  1. User taps a login button in AnimeMate
 *  2. `OAuthLauncher.launch` opens the MAL auth URL in the system
 *     browser via `Intent.ACTION_VIEW`
 *  3. User completes the login (which may itself route through
 *     Google / Apple / Facebook / X via MAL's provider buttons)
 *  4. MAL redirects to `animerec://auth?code=…&state=…`
 *  5. The OS routes that deep-link to
 *     [com.animerec.app.ui.auth.OAuthCallbackActivity] (registered in
 *     the manifest), which exchanges the code for tokens
 *  6. Callback activity finishes, returns control to MainActivity
 *
 * The user is bounced out to the system browser for steps 2-4 and
 * back into AnimeMate for steps 5-6. This is the standard,
 * provider-approved flow.
 */
object OAuthLauncher {

    private const val TAG = "OAuthLauncher"

    /**
     * Open [authUrl] in the system browser.
     *
     * @throws ActivityNotFoundException if no browser is installed.
     * The exception is caught and surfaced to the user via
     * [ErrorLogManager] plus a status text update on the calling
     * fragment, so a missing browser does not silently swallow the
     * auth flow.
     */
    fun launch(context: Context, authUrl: String) {
        val uri = Uri.parse(authUrl)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            // FLAG_ACTIVITY_NEW_TASK so we can launch from a non-Activity
            // context (e.g. an application context retained by a
            // singleton). FLAG_ACTIVITY_REQUIRE_NON_BROWSER on Android 11+
            // ensures the system picks a real browser, not a "browser
            // shim" that may not handle OAuth correctly.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
            }
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No browser installed; cannot launch OAuth", e)
            ErrorLogManager.logEvent(
                TAG,
                "ERROR",
                "No browser found for OAuth: ${e.message}"
            )
            // Surface the failure to the user via the bus. The login
            // fragment is observing the bus, so it will pick this up
            // and show a status text.
            AuthCallbackBus.postError(
                "no_browser",
                "No browser available. Please install a browser and try again."
            )
        }
    }
}
