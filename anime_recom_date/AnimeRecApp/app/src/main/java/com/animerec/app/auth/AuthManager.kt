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
package com.animerec.app.auth

import android.content.Context
import android.util.Log
import com.animerec.app.AnimeRecApp
import com.animerec.app.util.ErrorLogManager
import com.animerec.app.utils.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages user authentication with MyAnimeList.
 */
class AuthManager(private val context: Context) {
    
    private val TAG = "AuthManager"
    private val secureStorage = SecureStorage(context)
    private val refreshLock = Mutex() // Coroutine-safe lock for thread safety
    
    /**
     * Check if the user is authenticated.
     * @return true if the user has an access token.
     */
    fun isAuthenticated(): Boolean {
        return secureStorage.contains(SecureStorage.ACCESS_TOKEN_KEY)
    }
    
    /**
     * Get the current access token, refreshing if needed.
     * @param forceRefresh Force refresh the token even if it's not expired.
     * @return The access token, or null if not authenticated or refresh failed.
     */
    suspend fun getAccessToken(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        val accessToken = secureStorage.getString(SecureStorage.ACCESS_TOKEN_KEY)
        val expiryTime = secureStorage.getLong(SecureStorage.TOKEN_EXPIRY_KEY)
        
        if (accessToken.isEmpty()) {
            return@withContext null
        }
        
        val currentTime = System.currentTimeMillis()
        
        // Refresh the token if it's expired, will expire soon, or force refresh is requested
        if (forceRefresh || currentTime >= expiryTime - 5 * 60 * 1000) { // 5 minutes buffer
            return@withContext refreshLock.withLock {
                // Check again inside the lock to avoid multiple refreshes
                val currentToken = secureStorage.getString(SecureStorage.ACCESS_TOKEN_KEY)
                val currentExpiry = secureStorage.getLong(SecureStorage.TOKEN_EXPIRY_KEY)
                
                if (forceRefresh || currentTime >= currentExpiry - 5 * 60 * 1000) {
                    Log.d(TAG, "Access token expired or will expire soon, refreshing")
                    val refreshToken = secureStorage.getString(SecureStorage.REFRESH_TOKEN_KEY)
                    
                    if (refreshToken.isEmpty()) {
                        Log.e(TAG, "No refresh token found")
                        ErrorLogManager.logEvent(TAG, "ERROR", "No refresh token found — forcing logout")
                        logout()
                        return@withLock null
                    }
                    
                    val newToken = refreshToken(refreshToken)
                    return@withLock newToken
                } else {
                    // Another coroutine refreshed the token while we were waiting
                    return@withLock currentToken
                }
            }
        }
        
        return@withContext accessToken
    }
    
    /**
     * Save authentication tokens securely.
     */
    fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
        Log.d(TAG, "Saving auth tokens")
        secureStorage.putString(SecureStorage.ACCESS_TOKEN_KEY, accessToken)
        secureStorage.putString(SecureStorage.REFRESH_TOKEN_KEY, refreshToken)
        
        // Calculate expiry time (current time + expiresIn seconds)
        val expiryTime = System.currentTimeMillis() + (expiresIn * 1000)
        secureStorage.putLong(SecureStorage.TOKEN_EXPIRY_KEY, expiryTime)
    }
    
    /**
     * Refresh an expired access token.
     * @param refreshToken The refresh token.
     * @return The new access token, or null if refresh failed.
     */
    private suspend fun refreshToken(refreshToken: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Attempting to refresh token")
            
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            
            val formBody = FormBody.Builder()
                .add("client_id", AnimeRecApp.CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()
            
            val request = Request.Builder()
                .url(AnimeRecApp.MAL_TOKEN_URL)
                .post(formBody)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                response.body?.let { body ->
                    val jsonString = body.string()
                    val jsonObject = JSONObject(jsonString)
                    
                    val newAccessToken = jsonObject.getString("access_token")
                    val newRefreshToken = jsonObject.getString("refresh_token")
                    val expiresIn = jsonObject.getLong("expires_in")
                    
                    saveTokens(newAccessToken, newRefreshToken, expiresIn)
                    
                    Log.d(TAG, "Token refresh successful")
                    return@withContext newAccessToken
                }
            } else {
                val error = response.body?.string() ?: response.message
                Log.e(TAG, "Failed to refresh token: $error")
                ErrorLogManager.logEvent(TAG, "ERROR", "Token refresh failed: $error")
                logout()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during token refresh", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Token refresh exception: ${e.message}")
            logout()
        }
        
        return@withContext null
    }
    
    /**
     * Clear authentication data (logout).
     */
    fun logout() {
        Log.d(TAG, "Logging out, clearing auth tokens")
        secureStorage.remove(SecureStorage.ACCESS_TOKEN_KEY)
        secureStorage.remove(SecureStorage.REFRESH_TOKEN_KEY)
        secureStorage.remove(SecureStorage.TOKEN_EXPIRY_KEY)
        // H14: also wipe code_verifier, oauth state, and any cached profile
        // data so the next user on this device doesn't inherit state.
        secureStorage.remove(SecureStorage.CODE_VERIFIER_KEY)
    }
    
    /**
     * Exchange authorization code for tokens.
     * @param authCode The authorization code.
     * @param codeVerifier The code verifier.
     * @param redirectUri The redirect URI.
     * @return true if token exchange was successful.
     */
    suspend fun exchangeCodeForTokens(
        authCode: String,
        codeVerifier: String,
        redirectUri: String = AnimeRecApp.REDIRECT_URI
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Exchanging authorization code for tokens")
            
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            
            val formBody = FormBody.Builder()
                .add("client_id", AnimeRecApp.CLIENT_ID)
                .add("grant_type", "authorization_code")
                .add("code", authCode)
                .add("code_verifier", codeVerifier)
                .add("redirect_uri", redirectUri)
                .build()
            
            val request = Request.Builder()
                .url(AnimeRecApp.MAL_TOKEN_URL)
                .post(formBody)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                response.body?.let { body ->
                    val jsonString = body.string()
                    val jsonObject = JSONObject(jsonString)
                    
                    val accessToken = jsonObject.getString("access_token")
                    val refreshToken = jsonObject.getString("refresh_token")
                    val expiresIn = jsonObject.getLong("expires_in")
                    
                    saveTokens(accessToken, refreshToken, expiresIn)
                    
                    Log.d(TAG, "Token exchange successful")
                    return@withContext true
                }
            } else {
                val error = response.body?.string() ?: response.message
                Log.e(TAG, "Failed to exchange code for tokens: $error")
                ErrorLogManager.logEvent(TAG, "ERROR", "Token exchange failed: $error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during token exchange", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Token exchange exception: ${e.message}")
        }
        
        return@withContext false
    }
}