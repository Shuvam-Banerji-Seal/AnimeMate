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

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import com.animerec.app.R
import com.animerec.app.utils.OAuthUtil
import com.animerec.app.utils.SecureStorage

/**
 * True in-app WebView login — the user never leaves the AnimeMate app to
 * authenticate with MyAnimeList.
 *
 * The user explicitly asked for an in-app WebView so that:
 *  - The login UI feels native to AnimeMate (no browser chrome swap)
 *  - The deep-link redirect back into the app is reliable (the WebView
 *    handles `animerec://auth?…` itself, no OS hand-off required)
 *  - Emulators and devices with no Chrome Custom Tabs provider can still
 *    complete the login flow.
 *
 * The activity is fullscreen, listens for the redirect URL inside the
 * WebView via [WebViewClient.shouldOverrideUrlLoading], and on success
 * extracts the `code` + verifies `state` before handing off to the
 * existing [com.animerec.app.auth.AuthManager] for token exchange.
 *
 * Security notes:
 *  - JavaScript is enabled (MAL's login page needs it).
 *  - Cookies are persisted across sessions so the user does not have
 *    to re-enter their MAL credentials every launch.
 *  - Mixed content is allowed because MAL sometimes loads images over
 *    HTTP during the legacy OAuth handshake.
 *  - The activity is marked `excludeFromRecents` so the WebView's
 *    state (cookies, JavaScript console) is not leaked via the
 *    Recents screen.
 */
class WebViewLoginActivity : Activity() {

    private val TAG = "WebViewLoginActivity"
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView

    /**
     * Lazily-initialised token-exchange context. Stored as a field so
     * the WebViewClient inner class can reach it.
     */
    private var codeVerifier: String? = null
    private var expectedState: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_login)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.loading_indicator)
        statusText = findViewById(R.id.tv_status)
        urlText = findViewById(R.id.tv_url)

        // Pull the PKCE code verifier + CSRF state that LoginFragment
        // stored before launching us. They are one-shot — the secure
        // storage will be cleared as soon as the user completes (or
        // cancels) the auth flow.
        val secureStorage = SecureStorage(this)
        codeVerifier = secureStorage.getString(SecureStorage.CODE_VERIFIER_KEY)
        expectedState = secureStorage.getString(LoginFragment.STATE_KEY)

        if (codeVerifier.isNullOrEmpty() || expectedState.isNullOrEmpty()) {
            Log.w(TAG, "Missing code verifier or state; cannot proceed")
            statusText.text = getString(R.string.login_session_expired)
            webView.visibility = View.GONE
            AuthCallbackBus.postError("session_expired", "Please restart login")
            // Give the user a moment to read the message
            webView.postDelayed({ finish() }, 1500L)
            return
        }

        val authUrl = intent.getStringExtra(EXTRA_AUTH_URL)
        if (authUrl.isNullOrEmpty()) {
            Log.w(TAG, "No auth URL provided to WebViewLoginActivity")
            statusText.text = getString(R.string.login_session_expired)
            webView.visibility = View.GONE
            webView.postDelayed({ finish() }, 1500L)
            return
        }

        urlText.text = authUrl

        // Cookie / session persistence
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // WebView config
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            // MAL sometimes loads images over HTTP, especially in
            // older mobile-friendly pages. We still validate the URL
            // before any deep-link extraction in shouldOverrideUrlLoading.
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // WebView client
        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { urlText.text = it }
                statusText.text = ""
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }

            /**
             * Intercept any navigation to our redirect scheme
             * (`animerec://auth`) — that's the moment MAL hands us
             * the auth code. We override the navigation, swallow the
             * load, and run the rest of the OAuth flow in-process.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (isAuthRedirect(url)) {
                    handleAuthRedirect(Uri.parse(url))
                    return true
                }
                return false
            }

            // Older API for pre-Lollipop (we still override to be safe)
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && isAuthRedirect(url)) {
                    handleAuthRedirect(Uri.parse(url))
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                Log.w(TAG, "WebView error $errorCode on $failingUrl: $description")
                statusText.text = description ?: "WebView error"
                progressBar.visibility = View.GONE
            }
        }

        webView.loadUrl(authUrl)
    }

    private fun isAuthRedirect(url: String): Boolean {
        return url.startsWith("$REDIRECT_SCHEME://$REDIRECT_HOST/") ||
            url.startsWith("$REDIRECT_SCHEME://$REDIRECT_HOST")
    }

    /**
     * Process the OAuth callback. Pulls the auth code, verifies the
     * CSRF state, and either exchanges the code for tokens or surfaces
     * an error to the UI. Always finishes the activity at the end.
     */
    private fun handleAuthRedirect(uri: Uri) {
        progressBar.visibility = View.VISIBLE
        statusText.text = getString(R.string.login_callback_success)

        val code = OAuthUtil.extractAuthCode(uri)
        val state = OAuthUtil.extractState(uri)
        val error = OAuthUtil.extractError(uri)
        val secureStorage = SecureStorage(this)

        if (!error.isNullOrEmpty()) {
            // The user clicked "Deny" or MAL returned an error.
            Log.w(TAG, "OAuth error from provider: $error")
            statusText.text = "Provider error: $error"
            AuthCallbackBus.postError("oauth_error", "Provider error: $error")
            // Clear one-shot state so the user can retry
            secureStorage.remove(LoginFragment.STATE_KEY)
            secureStorage.remove(SecureStorage.CODE_VERIFIER_KEY)
            webView.postDelayed({ finish() }, 1500L)
            return
        }

        if (code.isNullOrEmpty()) {
            Log.w(TAG, "No auth code in redirect URI: $uri")
            statusText.text = "Invalid authorization response"
            AuthCallbackBus.postError("no_code", "No authorization code in callback")
            webView.postDelayed({ finish() }, 1500L)
            return
        }

        if (expectedState.isNullOrEmpty() || state != expectedState) {
            // State mismatch = possible CSRF. Abort.
            Log.w(TAG, "State mismatch: expected=$expectedState actual=$state")
            statusText.text = getString(R.string.login_state_mismatch)
            AuthCallbackBus.postError("state_mismatch", "Auth state mismatch")
            secureStorage.remove(LoginFragment.STATE_KEY)
            secureStorage.remove(SecureStorage.CODE_VERIFIER_KEY)
            webView.postDelayed({ finish() }, 1500L)
            return
        }

        // State OK — exchange the code for tokens.
        secureStorage.remove(LoginFragment.STATE_KEY)
        val verifier = codeVerifier
        if (verifier.isNullOrEmpty()) {
            statusText.text = getString(R.string.login_session_expired)
            AuthCallbackBus.postError("no_verifier", "Code verifier missing")
            webView.postDelayed({ finish() }, 1500L)
            return
        }
        val authManager = com.animerec.app.auth.AuthManager(this)
        val ok = runBlocking { authManager.exchangeCodeForTokens(code, verifier) }
        if (ok) {
            secureStorage.remove(SecureStorage.CODE_VERIFIER_KEY)
            AuthCallbackBus.postSuccess()
            // Tell the user what just happened before we pop.
            statusText.text = getString(R.string.login_callback_success)
            webView.postDelayed({ finish() }, 500L)
        } else {
            statusText.text = "Token exchange failed"
            AuthCallbackBus.postError("exchange_failed", "Token exchange failed")
            webView.postDelayed({ finish() }, 1500L)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        // Stop loading and clear history to free memory and prevent
        // any in-flight requests from holding references to this
        // destroyed activity.
        webView.stopLoading()
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    private fun <T> runBlocking(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }

    companion object {
        const val EXTRA_AUTH_URL = "extra_auth_url"
        // Animerec scheme constants — kept in sync with AndroidManifest
        // and OAuthCallbackActivity.
        private const val REDIRECT_SCHEME = "animerec"
        private const val REDIRECT_HOST = "auth"
    }
}
