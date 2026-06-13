/*
 * AnimeRec - Anime Recommendation App
 * Copyright (C) 2025 Shuvam Banerji Seal
 * Licensed under the MIT License.
 */
package com.animerec.app.api

import com.google.common.truth.Truth.assertThat
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test

/**
 * Verifies RetryInterceptor correctly classifies which HTTP methods are
 * safe to retry and which should not be retried.
 */
class RetryInterceptorTest {

    @Test
    fun `GET is idempotent and safe to retry`() {
        val request = Request.Builder().url("https://example.com/").get().build()
        assertThat(RetryInterceptor.isIdempotent(request)).isTrue()
    }

    @Test
    fun `HEAD is idempotent and safe to retry`() {
        val request = Request.Builder().url("https://example.com/").head().build()
        assertThat(RetryInterceptor.isIdempotent(request)).isTrue()
    }

    @Test
    fun `POST is not idempotent and must NOT be retried`() {
        val request = Request.Builder().url("https://example.com/").post(okhttp3.RequestBody.create(null, "")).build()
        assertThat(RetryInterceptor.isIdempotent(request)).isFalse()
    }

    @Test
    fun `PATCH is not idempotent and must NOT be retried`() {
        val request = Request.Builder().url("https://example.com/").patch(okhttp3.RequestBody.create(null, "")).build()
        assertThat(RetryInterceptor.isIdempotent(request)).isFalse()
    }

    @Test
    fun `PUT is idempotent per HTTP spec`() {
        // RFC 7231: PUT is idempotent. Many APIs misuse PUT for non-idempotent
        // operations, but for MAL the PATCH list update is not PUT. We treat PUT
        // as idempotent here per the spec.
        val request = Request.Builder().url("https://example.com/").put(okhttp3.RequestBody.create(null, "")).build()
        assertThat(RetryInterceptor.isIdempotent(request)).isTrue()
    }

    @Test
    fun `429 is a retryable status`() {
        assertThat(RetryInterceptor.isRetryableStatus(429)).isTrue()
    }

    @Test
    fun `500 502 503 504 are retryable status codes`() {
        assertThat(RetryInterceptor.isRetryableStatus(500)).isTrue()
        assertThat(RetryInterceptor.isRetryableStatus(502)).isTrue()
        assertThat(RetryInterceptor.isRetryableStatus(503)).isTrue()
        assertThat(RetryInterceptor.isRetryableStatus(504)).isTrue()
    }

    @Test
    fun `400 401 403 404 are not retryable`() {
        assertThat(RetryInterceptor.isRetryableStatus(400)).isFalse()
        assertThat(RetryInterceptor.isRetryableStatus(401)).isFalse()
        assertThat(RetryInterceptor.isRetryableStatus(403)).isFalse()
        assertThat(RetryInterceptor.isRetryableStatus(404)).isFalse()
    }
}
