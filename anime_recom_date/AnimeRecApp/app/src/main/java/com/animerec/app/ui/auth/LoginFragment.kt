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

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.animerec.app.R
import com.animerec.app.utils.OAuthUtil
import com.animerec.app.utils.SecureStorage

/**
 * Fragment for user login via MyAnimeList OAuth.
 *
 * The login flow is **external browser only**:
 *
 *  1. User taps "Login with MyAnimeList" (or any of the 4 provider
 *     quick-buttons: Google / Apple / Facebook / X).
 *  2. We generate a PKCE code_verifier + state, store them in
 *     [SecureStorage], and launch the MAL authorization URL in the
 *     system browser via [OAuthLauncher].
 *  3. The user logs in inside the system browser (which may itself
 *     route through their chosen provider — Google / Apple / etc.
 *     all block in-app WebView OAuth).
 *  4. MAL redirects to `animerec://auth?code=…&state=…`.
 *  5. The Android system delivers that deep-link intent to
 *     [OAuthCallbackActivity] (registered with its own intent-filter
 *     in the manifest). The callback activity verifies state,
 *     exchanges code for tokens, and posts the result to
 *     [AuthCallbackBus].
 *  6. This fragment (and any other active observer) reacts to the
 *     bus event, updates UI, and navigates to the next screen.
 *
 * Why external browser? Google, Apple, Facebook, and X all block
 * OAuth flows that originate from an embedded WebView. Custom Tabs
 * are flaky for some providers. The system browser is the only
 * universally-approved surface.
 */
class LoginFragment : Fragment() {

    private val TAG = "LoginFragment"
    private lateinit var viewModel: AuthViewModel

    // UI components
    private var loginButton: Button? = null
    private var progressBar: ProgressBar? = null
    private var statusText: TextView? = null
    private var googleButton: ImageButton? = null
    private var appleButton: ImageButton? = null
    private var facebookButton: ImageButton? = null
    private var twitterButton: ImageButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[AuthViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_login, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize UI components
        loginButton = view.findViewById(R.id.btn_login_mal)
        progressBar = view.findViewById(R.id.loading_indicator)
        statusText = view.findViewById(R.id.tv_status)
        googleButton = view.findViewById(R.id.btn_login_google)
        appleButton = view.findViewById(R.id.btn_login_apple)
        facebookButton = view.findViewById(R.id.btn_login_facebook)
        twitterButton = view.findViewById(R.id.btn_login_x)

        // All 5 login buttons route through the same OAuth flow in the
        // system browser. The provider enum drives the `prompt=login`
        // hint for the 4 third-party providers — MAL's own login uses
        // the saved session if any.
        loginButton?.setOnClickListener {
            initiateLogin(OAuthProvider.MAL, forceLogin = false)
        }
        googleButton?.setOnClickListener {
            initiateLogin(OAuthProvider.GOOGLE, forceLogin = true)
        }
        appleButton?.setOnClickListener {
            initiateLogin(OAuthProvider.APPLE, forceLogin = true)
        }
        facebookButton?.setOnClickListener {
            initiateLogin(OAuthProvider.FACEBOOK, forceLogin = true)
        }
        twitterButton?.setOnClickListener {
            initiateLogin(OAuthProvider.TWITTER, forceLogin = true)
        }

        // Observe the AuthViewModel state for the actual flow
        viewModel.authState.observe(viewLifecycleOwner) { authState ->
            when (authState) {
                is AuthViewModel.AuthState.Idle -> {
                    showLoading(false)
                }
                is AuthViewModel.AuthState.Loading -> {
                    showLoading(true)
                }
                is AuthViewModel.AuthState.Success -> {
                    showLoading(false)
                    navigateToNextScreen(authState.isSetupCompleted)
                }
                is AuthViewModel.AuthState.Error -> {
                    showLoading(false)
                    Log.e(TAG, "Auth error: ${authState.message}")
                    statusText?.text = authState.message
                }
            }
        }

        // Observe the global callback bus — covers the case where the
        // OAuthCallbackActivity finished before the fragment was
        // attached (e.g. user backgrounded the app, completed auth in
        // the browser, then re-foregrounded AnimeMate).
        AuthCallbackBus.events.observe(viewLifecycleOwner) { event ->
            when (event) {
                is AuthCallbackEvent.Success -> {
                    // Token exchange completed in the callback activity.
                    // The user has just returned to the app; check setup
                    // status and navigate.
                    viewModel.checkUserSetupStatus()
                }
                is AuthCallbackEvent.Error -> {
                    showLoading(false)
                    statusText?.text = "${event.message} (${event.code})"
                }
            }
        }

        if (viewModel.isAuthenticated()) {
            viewModel.checkUserSetupStatus()
        }

        // Belt-and-braces recovery: if a previous OAuthCallbackActivity
        // already finished token exchange but the process was killed
        // before the bus event was observed, the pending flag will
        // still be set in SecureStorage. Detect that here, clear the
        // flag, and navigate to home.
        val secureStorage = SecureStorage(requireContext())
        if (secureStorage.getBoolean(PENDING_AUTH_SUCCESS_KEY, false)) {
            Log.d(TAG, "Pending auth success flag detected — recovering")
            secureStorage.putBoolean(PENDING_AUTH_SUCCESS_KEY, false)
            // Also re-post the bus event so any active observer fires.
            AuthCallbackBus.postSuccess()
        }
    }

    override fun onResume() {
        super.onResume()
        // Same recovery check, but for the case where the fragment
        // was paused (e.g. user opened the browser) and the callback
        // activity finished while we were paused.
        val secureStorage = SecureStorage(requireContext())
        if (secureStorage.getBoolean(PENDING_AUTH_SUCCESS_KEY, false)) {
            Log.d(TAG, "Pending auth success flag detected on resume — recovering")
            secureStorage.putBoolean(PENDING_AUTH_SUCCESS_KEY, false)
            AuthCallbackBus.postSuccess()
        }
    }

    /**
     * Initiate the login process.
     *
     * @param provider Which MAL provider the user wants to log in with.
     *   Only affects the `prompt=login` hint; the actual authorization
     *   flow always uses the MAL endpoint, which can route to any
     *   provider.
     * @param forceLogin If true, MAL is told to re-show the login screen
     *   (useful when the user wants to switch accounts).
     */
    private fun initiateLogin(provider: OAuthProvider, forceLogin: Boolean = false) {
        val codeVerifier = OAuthUtil.generateCodeVerifier()
        val state = OAuthUtil.generateState()
        val secureStorage = SecureStorage(requireContext())
        secureStorage.putString(SecureStorage.CODE_VERIFIER_KEY, codeVerifier)
        secureStorage.putString(STATE_KEY, state)

        // MAL OAuth2 docs explicitly say only "plain" PKCE is supported
        // today. S256 would silently fail with an "invalid challenge"
        // error from MAL.
        val codeChallenge = OAuthUtil.generateCodeChallenge(codeVerifier, useS256 = false)
        val prompt = if (forceLogin) "login" else null
        val authUrl = OAuthUtil.buildAuthorizationUrl(
            codeChallenge = codeChallenge,
            state = state,
            useS256 = false,
            prompt = prompt
        )
        statusText?.text = getString(
            R.string.login_opening_browser,
            provider.displayName
        )
        showLoading(true)
        // Bounce the user out to the system browser. Google / Apple /
        // Facebook / X all block WebView-based OAuth, so this is the
        // only path that works for all 5 providers. If the launch
        // fails (e.g. no browser installed), the launcher has already
        // posted a structured error to AuthCallbackBus which the
        // fragment observes above — so we just return here.
        val launched = OAuthLauncher.launch(requireContext(), authUrl)
        if (!launched) {
            // The bus will deliver the error to the observer; we
            // just need to re-enable the buttons so the user can
            // retry once they install a browser.
            showLoading(false)
        }
    }

    private fun navigateToNextScreen(isSetupCompleted: Boolean) {
        if (isSetupCompleted) {
            findNavController().navigate(R.id.action_loginFragment_to_homeFragment)
        } else {
            findNavController().navigate(R.id.action_loginFragment_to_profileSetupFragment)
        }
    }

    private fun showLoading(isLoading: Boolean) {
        progressBar?.visibility = if (isLoading) View.VISIBLE else View.GONE
        loginButton?.isEnabled = !isLoading
        googleButton?.isEnabled = !isLoading
        appleButton?.isEnabled = !isLoading
        facebookButton?.isEnabled = !isLoading
        twitterButton?.isEnabled = !isLoading
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loginButton = null
        progressBar = null
        statusText = null
        googleButton = null
        appleButton = null
        facebookButton = null
        twitterButton = null
    }

    companion object {
        // SharedPreferences key for the OAuth state parameter. Kept here so
        // both this fragment and OAuthCallbackActivity agree on the same key.
        const val STATE_KEY = "oauth_state"
    }
}

/**
 * Which MAL identity provider the user wants to log in with. All
 * providers route through the same MAL authorization endpoint; the only
 * effect of this enum is to control the user-facing button label and
 * the `prompt=login` parameter that forces MAL to re-show the login
 * screen.
 */
enum class OAuthProvider(val displayName: String) {
    MAL("MyAnimeList"),
    GOOGLE("Google"),
    APPLE("Apple"),
    FACEBOOK("Facebook"),
    TWITTER("X")
}
