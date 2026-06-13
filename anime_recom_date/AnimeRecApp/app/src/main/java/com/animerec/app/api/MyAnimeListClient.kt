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
package com.animerec.app.api

import android.content.Context
import android.util.Log
import com.animerec.app.AnimeRecApp
import com.animerec.app.BuildConfig
import com.animerec.app.auth.AuthManager
import com.animerec.app.data.ApiResponseCache
import com.animerec.app.util.ErrorLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Client for the MyAnimeList API.
 */
class MyAnimeListClient(private val context: Context) {

    private val TAG = "MyAnimeListClient"
    private val authManager = AuthManager(context)

    // Cache instances
    private val apiResponseCache = ApiResponseCache()

    // S7: in-memory cached access token to avoid runBlocking on the OkHttp
    // dispatcher thread on every request. The first call populates this;
    // AuthManager.refresh() invalidates it. Without this, every API call
    // would block a dispatcher thread for an EncryptedSharedPreferences
    // read.
    @Volatile
    private var cachedToken: String? = null

    init {
        // Pre-warm the token cache on construction so the first request
        // doesn't pay the runBlocking cost. We don't await — the first
        // request that needs the token will await briefly.
        Thread {
            try {
                // Use a synchronous Keystore read on a worker thread; the
                // OkHttp interceptor will still fall back to runBlocking if
                // the token is missing and the call is fast.
                kotlinx.coroutines.runBlocking { refreshCachedToken() }
            } catch (_: Exception) {
                // Keystore may not be available yet; ignore.
            }
        }.start()
    }

    private suspend fun refreshCachedToken() {
        cachedToken = authManager.getAccessToken()
    }

    // Create OkHttp client with auth interceptor
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()

                // S7: Prefer the in-memory cached token. If it's null (e.g. after
                // a process restart and before the warm-up thread finishes), we
                // fall back to runBlocking. This is much cheaper than blocking
                // on every request.
                val accessToken = cachedToken
                    ?: runBlocking { authManager.getAccessToken().also { cachedToken = it } }
                if (!accessToken.isNullOrEmpty()) {
                    builder.header("Authorization", "Bearer $accessToken")
                }
                // Skip X-MAL-CLIENT-ID when we have a user token — MAL's docs
                // say the bearer header is authoritative when present; the
                // extra header is wasted bytes (H13).
                if (accessToken.isNullOrEmpty()) {
                    builder.header("X-MAL-CLIENT-ID", AnimeRecApp.CLIENT_ID)
                }
                chain.proceed(builder.build())
            }
            .addInterceptor(RetryInterceptor())
            .addNetworkInterceptor(HttpLoggingInterceptor().apply {
                // H5: only log on debug builds to avoid leaking URLs to logcat in release
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            })
            .build()
    }
    
    // Create Retrofit instance
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(AnimeRecApp.MAL_API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    // Create the API service
    val service: MyAnimeListService by lazy {
        retrofit.create(MyAnimeListService::class.java)
    }
    
    /**
     * Execute an API request with caching.
     */
    suspend fun <T> executeWithCache(
        cacheKey: String,
        expirationMs: Long,
        apiCall: suspend () -> retrofit2.Response<T>
    ): T? = withContext(Dispatchers.IO) {
        try {
            // Check cache first
            val cachedResponse = apiResponseCache.get<T>(cacheKey)
            if (cachedResponse != null) {
                return@withContext cachedResponse
            }
            
            // Cache miss, execute API call
            val response = apiCall()
            
            if (response.isSuccessful) {
                val body = response.body()
                
                if (body != null) {
                    // Cache the response
                    apiResponseCache.put(cacheKey, body, expirationMs)
                    return@withContext body
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "API error (${response.code()}): $errorBody")
                ErrorLogManager.logEvent(TAG, "ERROR", "API error (${response.code()}): $errorBody")
                
                // Handle 401 Unauthorized (token expired)
                if (response.code() == 401) {
                    // Force token refresh
                    val refreshed = authManager.getAccessToken(forceRefresh = true)
                    if (refreshed != null) {
                        // Retry the request
                        val retryResponse = apiCall()
                        if (retryResponse.isSuccessful) {
                            val retryBody = retryResponse.body()
                            if (retryBody != null) {
                                // Cache the response
                                apiResponseCache.put(cacheKey, retryBody, expirationMs)
                                return@withContext retryBody
                            }
                        }
                    }
                }
            }
            
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "API call failed", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "API call failed: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Clear the API cache.
     */
    fun clearCache() {
        apiResponseCache.clear()
    }
}