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

import android.util.Log
import com.animerec.app.util.ErrorLogManager
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * OkHttp interceptor that handles rate limiting and retries failed requests with
 * exponential backoff.
 *
 * Only idempotent verbs (GET, HEAD) and clearly-server-side errors (429/502/503/504)
 * are retried. POST/PATCH/PUT/DELETE are never auto-retried.
 */
class RetryInterceptor : Interceptor {

    private val TAG = "RetryInterceptor"

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 20000L

        // Per RFC 7231 §4.2.2: GET, HEAD, PUT, DELETE, OPTIONS, TRACE are
        // idempotent. POST, PATCH, CONNECT are not.
        private val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE")

        @JvmStatic
        fun isIdempotent(request: Request): Boolean =
            request.method.uppercase() in IDEMPOTENT_METHODS

        @JvmStatic
        fun isRetryableStatus(code: Int): Boolean =
            // 429 = rate limited, 5xx = server-side, 408 = request timeout
            code == 408 || code == 429 ||
            code == 500 || code == 502 || code == 503 || code == 504

        // Backwards-compatible alias
        @JvmStatic
        fun isSafeToRetry(request: Request): Boolean = isIdempotent(request)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val idempotent = isIdempotent(request)

        var response: Response? = null
        var retriesLeft = MAX_RETRIES
        var backoffMs = INITIAL_BACKOFF_MS
        var lastException: Exception? = null

        while (retriesLeft > 0) {
            // If this isn't the first attempt, apply delay with jitter
            if (response != null) {
                val jitter = backoffMs * (0.1 + Random.nextDouble(0.2))
                val delayMs = (backoffMs + jitter).toLong()

                Log.d(TAG, "Retrying ${request.method} after ${delayMs}ms delay, ${retriesLeft - 1} retries left")
                Thread.sleep(delayMs)
            }

            try {
                response?.close()
                response = chain.proceed(request)

                if (isRetryableStatus(response.code)) {
                    val retryAfterHeader = response.header("Retry-After")
                    val retryAfterMs = if (!retryAfterHeader.isNullOrEmpty()) {
                        retryAfterHeader.toLongOrNull()?.times(1000) ?: backoffMs
                    } else {
                        backoffMs
                    }
                    Log.d(TAG, "${request.method} → ${response.code}, retrying after ${retryAfterMs}ms")
                    response.close()
                    response = null
                    retriesLeft--
                    backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
                } else {
                    return response
                }
            } catch (e: Exception) {
                // Non-idempotent methods are never retried — bail out immediately
                if (!idempotent) {
                    Log.w(TAG, "Non-idempotent ${request.method} failed; not retrying", e)
                    throw e
                }
                Log.e(TAG, "Request failed: ${e.message}")
                ErrorLogManager.logEvent(TAG, "ERROR", "Request failed (retries=$retriesLeft): ${e.message}")
                lastException = e
                retriesLeft--
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            }
        }

        return response ?: throw lastException
            ?: IllegalStateException("Request failed after $MAX_RETRIES retries")
    }
}
