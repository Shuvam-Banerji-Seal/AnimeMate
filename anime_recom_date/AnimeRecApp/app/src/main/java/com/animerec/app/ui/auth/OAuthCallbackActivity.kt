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

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.animerec.app.AnimeRecApp
import com.animerec.app.utils.OAuthUtil
import com.animerec.app.utils.SecureStorage

/**
 * Transparent activity that receives the OAuth redirect from
 * Chrome Custom Tabs (or the system browser) and exchanges the code
 * for tokens.
 *
 * Why a separate activity:
 *  - **Single top:** when Chrome Custom Tabs redirects back, Android
 *    brings this activity to the front and hands us the deep-link Intent
 *    via [onNewIntent]. We don't have to worry about which activity was
 *    on top when the user clicked "Allow" in the browser.
 *  - **Decoupling:** the callback doesn't depend on LoginFragment being
 *    in the back stack. If the user re-opens the app after starting
 *    auth, LoginFragment is not yet attached; this activity handles
 *    the redirect without depending on UI state.
 *  - **TaskStackBuilder:** the activity is configured to clear the
 *    browser task from the back stack and bring the main activity to
 *    the front after token exchange.
 *
 * The activity is invisible (no UI); it persists in the foreground for
 * a few hundred milliseconds while the network round-trip completes,
 * then finishes and pops back to MainActivity which is told to render
 * the authenticated state.
 */
class OAuthCallbackActivity : Activity() {

    private val TAG = "OAuthCallbackActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data
        if (uri == null) {
            Log.w(TAG, "handleIntent called with null uri")
            finishWithError("no_uri", "OAuth callback opened without a URI")
            return
        }
        if (uri.scheme != "animerec" || uri.host != "auth") {
            Log.w(TAG, "Ignoring non-OAuth callback URI: $uri")
            finishWithError("bad_scheme", "Unexpected callback URI: $uri")
            return
        }

        // Capture values from the deep link
        val code = OAuthUtil.extractAuthCode(uri)
        val state = OAuthUtil.extractState(uri)
        val error = OAuthUtil.extractError(uri)

        if (!error.isNullOrEmpty()) {
            Log.w(TAG, "OAuth provider returned error: $error")
            finishWithError("oauth_error", "Provider error: $error")
            return
        }

        if (code.isNullOrEmpty()) {
            Log.w(TAG, "No auth code in callback URI: $uri")
            finishWithError("no_code", "No authorization code in callback")
            return
        }

        // Verify the CSRF state.
        val secureStorage = SecureStorage(this)
        val expectedState = secureStorage.getString(LoginFragment.STATE_KEY)
        if (expectedState.isNullOrEmpty() || expectedState != state) {
            Log.w(TAG, "State mismatch: expected=$expectedState actual=$state")
            // One-shot clear (whether the state matched or not, it's been used)
            secureStorage.remove(LoginFragment.STATE_KEY)
            finishWithError(
                "state_mismatch",
                "Auth state mismatch (possible CSRF). Please try again."
            )
            return
        }
        secureStorage.remove(LoginFragment.STATE_KEY)

        // Pull the code verifier and clear it after a successful exchange.
        val codeVerifier = secureStorage.getString(SecureStorage.CODE_VERIFIER_KEY)
        if (codeVerifier.isNullOrEmpty()) {
            finishWithError(
                "no_verifier",
                "Code verifier missing — please restart login"
            )
            return
        }

        // Hand off to AuthManager for the actual network exchange. We do
        // this synchronously on the main thread because the activity is
        // invisible anyway and we need to keep this alive until the
        // token response lands.
        val authManager = com.animerec.app.auth.AuthManager(this)
        val result = runBlocking { authManager.exchangeCodeForTokens(code, codeVerifier) }
        if (result) {
            secureStorage.remove(SecureStorage.CODE_VERIFIER_KEY)
            // Notify any observers on the LiveData singleton.
            AuthCallbackBus.postSuccess()
            Log.d(TAG, "Token exchange succeeded; finishing")
            bringMainToFront()
        } else {
            Log.w(TAG, "Token exchange failed")
            AuthCallbackBus.postError("exchange_failed", "Token exchange failed")
            bringMainToFront()
        }
        finish()
    }

    private fun finishWithError(code: String, message: String) {
        Log.w(TAG, "OAuth callback error: $code — $message")
        AuthCallbackBus.postError(code, message)
        bringMainToFront()
        finish()
    }

    private fun bringMainToFront() {
        // Bring the existing main task to the front; do not start a new
        // instance. We use the package's launch intent to avoid hard-coding
        // the activity class name (AnimeRecApp is the Application class,
        // not an Activity).
        val mainIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (mainIntent != null) {
            mainIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            try {
                startActivity(mainIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not bring main activity to front: ${e.message}")
            }
        }
    }
}

/**
 * Tiny LiveData singleton for propagating OAuth callback results from the
 * (invisible) OAuthCallbackActivity to any active observer (typically
 * LoginFragment or MainActivity). Using a process-wide singleton avoids
 * passing a reference through the intent (which would re-launch the
 * activity on the wrong process).
 */
object AuthCallbackBus {
    private val _events = MutableLiveData<AuthCallbackEvent>()
    val events: androidx.lifecycle.LiveData<AuthCallbackEvent> = _events

    fun postSuccess() {
        _events.postValue(AuthCallbackEvent.Success)
    }

    fun postError(code: String, message: String) {
        _events.postValue(AuthCallbackEvent.Error(code, message))
    }
}

sealed class AuthCallbackEvent {
    data object Success : AuthCallbackEvent()
    data class Error(val code: String, val message: String) : AuthCallbackEvent()
}

/**
 * runBlocking shim so we can call a suspend function from a non-suspend
 * activity callback without pulling in the full coroutines runtime at
 * this call site. The function exchanges at most once per callback.
 */
private fun <T> runBlocking(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }
