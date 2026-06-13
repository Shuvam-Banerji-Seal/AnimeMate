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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import com.animerec.app.util.ErrorLogManager

/**
 * Launches the MAL OAuth authorization URL in the best available surface:
 *
 * 1. **Chrome Custom Tabs** (AndroidX Browser) — the in-app browser that
 *    shares cookies with the system browser, provides back-button and
 *    toolbar, and most importantly auto-handles the deep-link redirect
 *    back to AnimeMate when the user clicks "Allow".
 * 2. **System browser** — fallback if no Custom Tabs provider is installed
 *    (rare; the OS always ships Chrome on Play-certified devices).
 *
 * Whichever surface is used, the resulting `animerec://auth?code=…` redirect
 * is delivered back to AnimeMate via the standard intent-filter on
 * [com.animerec.app.ui.auth.OAuthCallbackActivity], which is registered
 * separately in the manifest.
 */
object OAuthLauncher {

    private const val TAG = "OAuthLauncher"

    /**
     * Try Chrome Custom Tabs first; if unavailable, fall back to the system
     * browser. The intent-filter on [OAuthCallbackActivity] is what catches
     * the deep-link redirect, regardless of which surface was used.
     */
    fun launch(context: Context, authUrl: String) {
        val uri = Uri.parse(authUrl)
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        // Try to find a Custom Tabs provider. If one is bound, launch the URL
        // via the in-app tab. Otherwise fall back to ACTION_VIEW.
        val connection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(
                name: ComponentName,
                client: CustomTabsClient
            ) {
                try {
                    customTabsIntent.launchUrl(context, uri)
                } catch (e: Exception) {
                    Log.w(TAG, "Custom Tabs launchUrl failed, falling back", e)
                    ErrorLogManager.logEvent(TAG, "WARN", "CustomTabs launch failed: ${e.message}")
                    launchSystemBrowser(context, uri)
                } finally {
                    try {
                        context.unbindService(this)
                    } catch (_: Exception) { /* already unbound */ }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // No-op
            }
        }

        try {
            val intent = Intent("androidx.browser.customtabs.action.CustomTabsService")
                .setPackage("com.android.chrome")
            val bound = context.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!bound) {
                // No Custom Tabs provider; fall back immediately.
                launchSystemBrowser(context, uri)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Custom Tabs bind failed; falling back to system browser", e)
            launchSystemBrowser(context, uri)
        }
    }

    private fun launchSystemBrowser(context: Context, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No browser installed; surfacing to user", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "No browser found: ${e.message}")
            // Last resort: the user will see a no-browser error in the
            // login fragment, which is acceptable — the system will not
            // silently fail.
        }
    }
}
