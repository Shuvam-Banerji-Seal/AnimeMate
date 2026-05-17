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
package com.animerec.app.ui.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.animerec.app.R
import com.animerec.app.utils.OAuthUtil
import com.animerec.app.utils.SecureStorage
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Fragment for user login via MyAnimeList OAuth.
 */
class LoginFragment : Fragment() {
    
    private val TAG = "LoginFragment"
    private lateinit var viewModel: AuthViewModel
    
    // UI components
    private var loginButton: MaterialButton? = null
    private var progressBar: ProgressBar? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[AuthViewModel::class.java]
        
        // Check for OAuth redirect
        activity?.intent?.data?.let { uri ->
            if (uri.scheme == "animerec" && uri.host == "auth") {
                handleAuthRedirect(uri)
            }
        }
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
        
        // Set up button click
        loginButton?.setOnClickListener {
            initiateLogin()
        }
        
        // Observe authentication state
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
                }
            }
        }
        
        // Check if already authenticated
        if (viewModel.isAuthenticated()) {
            viewModel.checkUserSetupStatus()
        }
    }
    
    /**
     * Handle intent from MainActivity for deep link handling.
     */
    fun handleIntent(intent: Intent) {
        intent.data?.let { uri ->
            if (uri.scheme == "animerec" && uri.host == "auth") {
                handleAuthRedirect(uri)
            }
        }
    }
    
    /**
     * Initiate the login process by opening the MAL authorization page.
     */
    private fun initiateLogin() {
        // Generate and store PKCE code verifier
        val codeVerifier = OAuthUtil.generateCodeVerifier()
        val secureStorage = SecureStorage(requireContext())
        secureStorage.putString(SecureStorage.CODE_VERIFIER_KEY, codeVerifier)
        
        // Generate code challenge
        val codeChallenge = OAuthUtil.generateCodeChallenge(codeVerifier)
        
        // Build authorization URL
        val authUrl = OAuthUtil.buildAuthorizationUrl(codeChallenge)
        
        // Open browser for authentication
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
        startActivity(intent)
    }
    
    /**
     * Handle redirect after successful MAL authorization.
     */
    private fun handleAuthRedirect(uri: Uri) {
        viewModel.authState.value = AuthViewModel.AuthState.Loading
        
        // Extract auth code from URI
        val authCode = OAuthUtil.extractAuthCode(uri)
        if (authCode.isNullOrEmpty()) {
            viewModel.authState.value = AuthViewModel.AuthState.Error("Invalid authorization response")
            return
        }
        
        // Get stored code verifier
        val secureStorage = SecureStorage(requireContext())
        val codeVerifier = secureStorage.getString(SecureStorage.CODE_VERIFIER_KEY)
        if (codeVerifier.isEmpty()) {
            viewModel.authState.value = AuthViewModel.AuthState.Error("Missing code verifier")
            return
        }
        
        // Exchange code for tokens
        lifecycleScope.launch {
            val success = viewModel.exchangeCodeForTokens(authCode, codeVerifier)
            if (success) {
                viewModel.checkUserSetupStatus()
            } else {
                viewModel.authState.value = 
                    AuthViewModel.AuthState.Error("Failed to exchange code for tokens")
            }
        }
    }
    
    /**
     * Navigate to the appropriate screen based on setup status.
     */
    private fun navigateToNextScreen(isSetupCompleted: Boolean) {
        if (isSetupCompleted) {
            // If setup is completed, navigate to home screen
            findNavController().navigate(R.id.action_loginFragment_to_homeFragment)
        } else {
            // If setup is not completed, navigate to profile setup
            findNavController().navigate(R.id.action_loginFragment_to_profileSetupFragment)
        }
    }
    
    /**
     * Show/hide loading indicator.
     */
    private fun showLoading(isLoading: Boolean) {
        progressBar?.visibility = if (isLoading) View.VISIBLE else View.GONE
        loginButton?.isEnabled = !isLoading
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        loginButton = null
        progressBar = null
    }
}