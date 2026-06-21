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
import com.animerec.app.utils.OAuthUtil
import com.animerec.app.utils.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent activity that receives the OAuth redirect from
 * the system browser and exchanges the code for tokens.
 *
 * Why a separate activity:
 *  - **Decoupling:** the callback doesn't depend on LoginFragment
 *    being in the back stack. If the user re-opens the app after
 *    starting auth, LoginFragment is not yet attached; this
 *    activity handles the redirect without depending on UI state.
 *  - **Robustness:** even if MainActivity has been killed for
 *    memory pressure, this activity receives the deep-link intent
 *    fresh. We exchange the code for tokens, store them in
 *    SecureStorage, and bring MainActivity back to the front.
 *  - **Cleanup:** when the activity finishes, the browser task
 *    is removed from the recents list (because of
 *    `excludeFromRecents="true"` on this activity).
 *
 * The activity is invisible (no UI); it persists in the foreground
 * for ~1 second while the network round-trip completes, then
 * finishes and pops back to MainActivity which is told to render
 * the authenticated state.
 */
class OAuthCallbackActivity : Activity() {

    private val TAG = "OAuthCallbackActivity"

    /**
     * Per-activity coroutine scope. We use this for the token
     * exchange instead of `runBlocking` on the main thread (which
     * would block the UI and could deadlock if the network layer
     * also uses coroutines). Cancelled in [onDestroy].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Critical: store the new intent so subsequent getIntent()
        // calls (e.g. after configuration change) see the latest URI.
        setIntent(intent)
        if (intent != null) handleIntent(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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

        // Verify the CSRF state BEFORE doing anything else.
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
        // State OK — clear it now (one-shot) so a re-used URI can't match.
        secureStorage.remove(LoginFragment.STATE_KEY)

        // Pull the code verifier.
        val codeVerifier = secureStorage.getString(SecureStorage.CODE_VERIFIER_KEY)
        if (codeVerifier.isNullOrEmpty()) {
            finishWithError(
                "no_verifier",
                "Code verifier missing — please restart login"
            )
            return
        }

        // Hand off to AuthManager for the actual network exchange. Use
        // a coroutine instead of `runBlocking` so the main thread is
        // free to handle UI events (e.g. user taps Back, system
        // sends lifecycle callback, etc.).
        scope.launch {
            val result = try {
                val authManager = com.animerec.app.auth.AuthManager(this@OAuthCallbackActivity)
                withContext(Dispatchers.IO) {
                    authManager.exchangeCodeForTokens(code, codeVerifier)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during token exchange", e)
                com.animerec.app.util.ErrorLogManager.logEvent(
                    TAG,
                    "ERROR",
                    "Token exchange exception: ${e.javaClass.simpleName} ${e.message}"
                )
                false
            }

            if (result) {
                // Clear the verifier (one-shot).
                secureStorage.remove(SecureStorage.CODE_VERIFIER_KEY)
                Log.d(TAG, "Token exchange succeeded; bringing main to front")
                // **Belt-and-braces persistence**: also write a
                // pending-auth-success flag to SecureStorage. This is
                // redundant with [AuthCallbackBus.postSuccess] in the
                // happy case, but it survives the worst-case scenario
                // where (a) the process is killed between bus-post
                // and main-activity-resume, or (b) the MainActivity /
                // LoginFragment is destroyed and recreated between
                // bus-post and observer-rebind. The fragment checks
                // this flag in onResume and triggers navigation if
                // it's set, then clears it.
                secureStorage.putBoolean(PENDING_AUTH_SUCCESS_KEY, true)
                AuthCallbackBus.postSuccess()
            } else {
                Log.w(TAG, "Token exchange failed")
                AuthCallbackBus.postError("exchange_failed", "Token exchange failed")
            }
            bringMainToFront()
            finish()
        }
    }

    private fun finishWithError(code: String, message: String) {
        Log.w(TAG, "OAuth callback error: $code — $message")
        AuthCallbackBus.postError(code, message)
        bringMainToFront()
        finish()
    }

    /**
     * Bring MainActivity to the foreground.
     *
     * Three scenarios:
     *  1. MainActivity already exists in the background → bring it
     *     to front via [Intent.FLAG_ACTIVITY_CLEAR_TOP] +
     *     [Intent.FLAG_ACTIVITY_SINGLE_TOP]. No new instance.
     *  2. MainActivity was killed by Android → launch a new
     *     instance via [Intent.FLAG_ACTIVITY_NEW_TASK]. The new
     *     instance will run SplashFragment, which checks
     *     authentication and navigates to home.
     *  3. MainActivity is the launcher → reuse the launch intent
     *     from [PackageManager.getLaunchIntentForPackage].
     *
     * All three are handled by setting both NEW_TASK and CLEAR_TOP.
     */
    private fun bringMainToFront() {
        try {
            // First try: explicit class reference. This is the most
            // reliable way to ensure we bring up exactly MainActivity
            // and not any other activity that might match the
            // launch intent.
            val mainIntent = Intent().apply {
                setClassName(this@OAuthCallbackActivity, "com.animerec.app.ui.MainActivity")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(mainIntent)
        } catch (e: Exception) {
            // Second try: packageManager.getLaunchIntentForPackage.
            // This works when MainActivity is the LAUNCHER activity
            // (which it is). If MainActivity has been killed for
            // memory, this will create a new instance.
            Log.w(TAG, "Class-based launch failed: ${e.message}; trying packageManager")
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    startActivity(launchIntent)
                } else {
                    Log.e(TAG, "No launch intent available — user must reopen the app")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Could not bring main activity to front", e2)
                // Last resort: the user will see the OAuthCallbackActivity
                // finish and land back on whatever was last in front
                // (probably the browser). Tokens are stored in
                // SecureStorage so the user can re-open the app and
                // skip directly to home via SplashFragment.
            }
        }
    }
}

/**
 * Tiny LiveData singleton for propagating OAuth callback results from
 * the (invisible) OAuthCallbackActivity to any active observer
 * (typically LoginFragment or MainActivity). Using a process-wide
 * singleton avoids passing a reference through the intent (which
 * would re-launch the activity on the wrong process).
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
 * SharedPreferences key (in [SecureStorage]) for the pending-auth-success
 * flag. Written by [OAuthCallbackActivity] after a successful token
 * exchange; read by [LoginFragment.onResume] to recover from the case
 * where the process / activity was destroyed between the bus-post and
 * the fragment re-binding to the bus.
 */
const val PENDING_AUTH_SUCCESS_KEY = "pending_auth_success"
