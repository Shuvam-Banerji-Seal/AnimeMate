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
package com.animerec.app.utils

import android.net.Uri
import com.animerec.app.AnimeRecApp
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Utility class for OAuth PKCE flow with MyAnimeList.
 */
object OAuthUtil {
    
    // PKCE spec: verifier must be 43..128 chars from the unreserved set
    // [A-Z][a-z][0-9]-._~
    private const val MIN_VERIFIER_LENGTH = 43
    private const val MAX_VERIFIER_LENGTH = 128
    
    // State parameter length for CSRF protection (RFC 6749 §10.12)
    private const val STATE_LENGTH = 32

    /**
     * Generate a cryptographically-random code verifier for PKCE.
     */
    fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        // 64 random bytes → 86 base64url chars after encoding, well within the 43-128 spec range.
        val bytes = ByteArray(64)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            .take(MAX_VERIFIER_LENGTH)
    }

    /**
     * Generate a cryptographically-random state parameter for CSRF protection.
     * Per RFC 6749 §10.12 and OAuth 2.0 Security BCP, the state value must be
     * unguessable and bound to the originating user session.
     */
    fun generateState(): String {
        val secureRandom = SecureRandom()
        val bytes = ByteArray(STATE_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Generate code challenge from code verifier using S256 method (RFC 7636).
     * Falls back to "plain" only when the caller explicitly requests it (for
     * legacy servers that don't accept S256).
     */
    fun generateCodeChallenge(codeVerifier: String, useS256: Boolean = true): String {
        require(codeVerifier.length in MIN_VERIFIER_LENGTH..MAX_VERIFIER_LENGTH) {
            "Code verifier length ${codeVerifier.length} outside PKCE range $MIN_VERIFIER_LENGTH..$MAX_VERIFIER_LENGTH"
        }
        require(codeVerifier.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~" }) {
            "Code verifier contains non-PKCE characters"
        }
        if (!useS256) return codeVerifier
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
    
    /**
     * Build the authorization URL for MyAnimeList.
     *
     * @param codeChallenge The PKCE code challenge (S256 of the verifier)
     * @param state Anti-CSRF state value, must be validated on callback
     * @param useS256 If true, uses S256 challenge method (recommended); else "plain"
     */
    fun buildAuthorizationUrl(
        codeChallenge: String,
        state: String,
        useS256: Boolean = true
    ): String {
        return Uri.parse(AnimeRecApp.MAL_AUTH_URL)
            .buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", AnimeRecApp.CLIENT_ID)
            .appendQueryParameter("redirect_uri", AnimeRecApp.REDIRECT_URI)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter(
                "code_challenge_method",
                if (useS256) "S256" else "plain"
            )
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }
    
    /**
     * Extract authorization code from redirect URI.
     */
    fun extractAuthCode(uri: Uri): String? {
        return uri.getQueryParameter("code")
    }

    /**
     * Extract the state parameter from the redirect URI.
     * Used to verify the callback is for an auth flow we initiated.
     */
    fun extractState(uri: Uri): String? {
        return uri.getQueryParameter("state")
    }
    
    /**
     * Check if redirect URI contains an error.
     */
    fun extractError(uri: Uri): String? {
        return uri.getQueryParameter("error")
    }
}

