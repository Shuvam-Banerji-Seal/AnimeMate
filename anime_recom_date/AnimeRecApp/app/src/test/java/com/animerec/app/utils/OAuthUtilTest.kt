/*
 * AnimeRec - Anime Recommendation App
 * Copyright (C) 2025 Shuvam Banerji Seal
 * Licensed under the MIT License.
 */
package com.animerec.app.utils

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Base64

/**
 * Tests for OAuthUtil — verifies the PKCE state parameter and S256 challenge
 * are correctly generated and embedded in the authorization URL.
 *
 * Uses Robolectric so [Uri.parse] is available.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OAuthUtilTest {

    // ─── S2/S3: state parameter & S256 challenge ─────────────────────────

    @Test
    fun `buildAuthorizationUrl includes state parameter`() {
        val state = "test_state_12345"
        val url = OAuthUtil.buildAuthorizationUrl("challenge", state = state)
        assertTrue(
            "URL must contain a state parameter (S2): $url",
            url.contains("state=") && url.contains(state)
        )
    }

    @Test
    fun `buildAuthorizationUrl uses S256 code challenge method by default`() {
        val url = OAuthUtil.buildAuthorizationUrl("challenge", state = "s")
        assertTrue("URL must declare S256 (S3): $url", url.contains("code_challenge_method=S256"))
    }

    @Test
    fun `buildAuthorizationUrl always passes the same response_type and client_id`() {
        val url = OAuthUtil.buildAuthorizationUrl("challenge", state = "s")
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id="))
        assertTrue(url.contains("redirect_uri=animerec%3A%2F%2Fauth"))
    }

    // ─── S2: state must be extracted & verified ──────────────────────────

    @Test
    fun `extractState returns the state from redirect URI`() {
        val uri = Uri.parse("animerec://auth?code=abc&state=expected_state_value")
        assertEquals("expected_state_value", OAuthUtil.extractState(uri))
    }

    @Test
    fun `extractState returns null when no state in URI`() {
        val uri = Uri.parse("animerec://auth?code=abc")
        assertNull(OAuthUtil.extractState(uri))
    }

    @Test
    fun `extractError returns the error from URI`() {
        val uri = Uri.parse("animerec://auth?error=access_denied&error_description=user+denied")
        assertEquals("access_denied", OAuthUtil.extractError(uri))
    }

    // ─── PKCE spec: verifier must be 43-128 chars from unreserved set ────

    @Test
    fun `code verifier length is within 43-128 char PKCE spec`() {
        val verifier = OAuthUtil.generateCodeVerifier()
        assertTrue(
            "Verifier length ${verifier.length} outside 43..128",
            verifier.length in 43..128
        )
    }

    @Test
    fun `code verifier uses only unreserved PKCE characters`() {
        val verifier = OAuthUtil.generateCodeVerifier()
        // PKCE: [A-Z][a-z][0-9]-._~
        assertTrue(
            "Verifier contains non-PKCE chars: $verifier",
            verifier.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~" }
        )
    }

    @Test
    fun `code challenge is S256 of verifier, not verifier itself`() {
        val verifier = OAuthUtil.generateCodeVerifier()
        val challenge = OAuthUtil.generateCodeChallenge(verifier)
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        assertEquals(
            "Challenge must be S256(verifier) — not the verifier itself (S3)",
            expected,
            challenge
        )
        assertNotEquals("Challenge must not equal verifier for S256", verifier, challenge)
    }

    @Test
    fun `code verifier generator yields unique values`() {
        val a = OAuthUtil.generateCodeVerifier()
        val b = OAuthUtil.generateCodeVerifier()
        assertNotEquals("Two consecutive verifiers must differ", a, b)
    }

    // ─── extractAuthCode still works ─────────────────────────────────────

    @Test
    fun `extractAuthCode returns the code from URI`() {
        val uri = Uri.parse("animerec://auth?code=my_auth_code&state=my_state")
        assertEquals("my_auth_code", OAuthUtil.extractAuthCode(uri))
    }

    @Test
    fun `extractAuthCode returns null when no code present`() {
        val uri = Uri.parse("animerec://auth?error=denied")
        assertNull(OAuthUtil.extractAuthCode(uri))
    }
}
