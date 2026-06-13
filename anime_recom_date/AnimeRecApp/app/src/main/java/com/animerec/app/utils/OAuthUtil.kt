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
     * Generate code challenge from code verifier.
     *
     * IMPORTANT: MyAnimeList's official OAuth2 documentation explicitly states
     * that "Currently, only the `plain` method is supported." (See
     * https://myanimelist.net/apiconfig/references/authorization#step-1-…)
     * We therefore default to `plain`. The `useS256` parameter is kept for
     * future-proofing — the day MAL adds S256 support, only one call site
     * needs to flip the bit.
     */
    fun generateCodeChallenge(codeVerifier: String, useS256: Boolean = false): String {
        require(codeVerifier.length in MIN_VERIFIER_LENGTH..MAX_VERIFIER_LENGTH) {
            "Code verifier length ${codeVerifier.length} outside PKCE range $MIN_VERIFIER_LENGTH..$MAX_VERIFIER_LENGTH"
        }
        require(codeVerifier.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~" }) {
            "Code verifier contains non-PKCE characters"
        }
        // MAL accepts only "plain" today. The S256 path is dead code but kept
        // for the day MAL flips the switch.
        return if (useS256) {
            val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        } else {
            codeVerifier
        }
    }

    /**
     * Build the authorization URL for MyAnimeList.
     *
     * @param codeChallenge The PKCE code challenge
     * @param state Anti-CSRF state value, must be validated on callback
     * @param useS256 If true, uses S256 challenge method; else "plain" (MAL default)
     * @param prompt "login" to force re-login, "none" to silently check, default
     *        lets MAL decide
     */
    fun buildAuthorizationUrl(
        codeChallenge: String,
        state: String,
        useS256: Boolean = false,
        prompt: String? = null
    ): String {
        val builder = Uri.parse(AnimeRecApp.MAL_AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", AnimeRecApp.CLIENT_ID)
            .appendQueryParameter("redirect_uri", AnimeRecApp.REDIRECT_URI)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter(
                "code_challenge_method",
                if (useS256) "S256" else "plain"
            )
            .appendQueryParameter("state", state)
        if (prompt != null) {
            builder.appendQueryParameter("prompt", prompt)
        }
        return builder.build().toString()
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

