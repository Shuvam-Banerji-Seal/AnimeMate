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

import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.animerec.app.R
import com.animerec.app.utils.OAuthUtil
import com.animerec.app.utils.SecureStorage
import kotlinx.coroutines.launch

/**
 * Fragment for user login via MyAnimeList OAuth.
 *
 * The login flow:
 *  1. User taps the "Log in with MyAnimeList" button (or any of the
 *     provider quick-buttons: Google / Apple / Facebook / X).
 *  2. We generate a PKCE code_verifier + state, store them in
 *     [SecureStorage], and launch the MAL authorization URL in a
 *     Chrome Custom Tab via [OAuthLauncher].
 *  3. The user logs in inside the Custom Tab, taps "Allow", and MAL
 *     redirects to `animerec://auth?code=…&state=…`.
 *  4. The Android system delivers that deep-link intent to our
 *     [OAuthCallbackActivity] (registered with its own intent-filter
 *     in the manifest). The callback activity verifies state, exchanges
 *     code for tokens, and posts the result to [AuthCallbackBus].
 *  5. This fragment (and any other active observer) reacts to the
 *     bus event, updates UI, and navigates to the next screen.
 *
 * If the user is signed in already, the fragment is short-circuited
 * to the post-login destination.
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
    private var inAppButton: Button? = null

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
        inAppButton = view.findViewById(R.id.btn_login_inapp)

        // Main MAL login button
        loginButton?.setOnClickListener {
            initiateLogin(OAuthProvider.MAL)
        }
        // Provider quick-buttons. Each delegates to the same OAuth flow
        // because MAL funnels all providers through the same
        // /v1/oauth2/authorize endpoint. The "prompt=login" parameter
        // forces MAL to re-show the login screen even if the user is
        // already logged in to the chosen provider in the system browser.
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
        // "Log in inside the app" — uses the in-app WebView path. The
        // user does not leave AnimeMate for the auth flow.
        inAppButton?.setOnClickListener {
            initiateLogin(OAuthProvider.MAL, forceLogin = false, inApp = true)
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
    }

    /**
     * Initiate the login process. The user explicitly asked for an
     * in-app login experience, so we launch the [WebViewLoginActivity]
     * by default. The Custom-Tabs / system-browser path is still
     * available via [OAuthLauncher] for users on devices where the
     * WebView doesn't behave well (e.g. custom ROMs with broken
     * third-party cookie policies).
     *
     * @param provider Which MAL provider the user wants to log in with
     *   (only affects the `prompt=login` hint; the actual authorization
     *   flow always uses the MAL endpoint, which can route to any provider).
     * @param forceLogin If true, MAL is told to re-show the login screen
     *   (useful when the user wants to switch accounts).
     * @param inApp If true (default), launch the in-app WebView login.
     *   If false, fall back to Chrome Custom Tabs / system browser.
     */
    private fun initiateLogin(
        provider: OAuthProvider,
        forceLogin: Boolean = false,
        inApp: Boolean = true
    ) {
        val codeVerifier = OAuthUtil.generateCodeVerifier()
        val state = OAuthUtil.generateState()
        val secureStorage = SecureStorage(requireContext())
        secureStorage.putString(SecureStorage.CODE_VERIFIER_KEY, codeVerifier)
        secureStorage.putString(STATE_KEY, state)

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
        if (inApp) {
            launchInAppBrowser(authUrl)
        } else {
            OAuthLauncher.launch(requireContext(), authUrl)
        }
    }

    /**
     * Open the auth URL in our in-app WebView. The user never leaves
     * AnimeMate — the WebView intercepts the `animerec://auth?…`
     * redirect itself and runs the token exchange in-process.
     */
    private fun launchInAppBrowser(authUrl: String) {
        val intent = android.content.Intent(
            requireContext(),
            WebViewLoginActivity::class.java
        ).apply {
            putExtra(WebViewLoginActivity.EXTRA_AUTH_URL, authUrl)
        }
        startActivity(intent)
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
        inAppButton?.isEnabled = !isLoading
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
        inAppButton = null
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
