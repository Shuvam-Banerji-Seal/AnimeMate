/*
 * AnimeRec - Anime Recomendación App
 * Copyright (C) 2025 Shuvam Banerji Seal
 * Licensed under the MIT License.
 */
package com.animerec.app.ui.auth

import android.net.Uri
import com.animerec.app.utils.OAuthUtil
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the OAuthLauncher and the new login layout.
 *
 * Note: the actual [OAuthLauncher.launch] requires a bound Custom Tabs
 * service connection, which Robolectric cannot easily provide. Instead
 * we test:
 *  - The URL builder properly encodes the prompt parameter
 *  - The OAuthProvider enum covers all 5 MAL providers
 *  - The state param is embedded and extractable
 *
 * For full Custom Tabs verification, see the instrumented tests
 * (connectedAndroidTest) which run on a real device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OAuthFlowTest {

    @Test
    fun `OAuthProvider enum has all 5 MAL providers`() {
        val providers = OAuthProvider.values()
        assertThat(providers).hasLength(5)
        // Order matters for UI (the buttons render in this order)
        assertThat(providers.map { it.name })
            .containsExactly("MAL", "GOOGLE", "APPLE", "FACEBOOK", "TWITTER")
            .inOrder()
    }

    @Test
    fun `OAuthProvider display names are human readable`() {
        assertThat(OAuthProvider.MAL.displayName).isEqualTo("MyAnimeList")
        assertThat(OAuthProvider.GOOGLE.displayName).isEqualTo("Google")
        assertThat(OAuthProvider.APPLE.displayName).isEqualTo("Apple")
        assertThat(OAuthProvider.FACEBOOK.displayName).isEqualTo("Facebook")
        assertThat(OAuthProvider.TWITTER.displayName).isEqualTo("X")
    }

    @Test
    fun `buildAuthorizationUrl includes prompt login when forceLogin is set`() {
        val url = OAuthUtil.buildAuthorizationUrl(
            codeChallenge = "challenge",
            state = "s",
            prompt = "login"
        )
        assertThat(url).contains("prompt=login")
    }

    @Test
    fun `buildAuthorizationUrl omits prompt when null`() {
        val url = OAuthUtil.buildAuthorizationUrl(
            codeChallenge = "challenge",
            state = "s",
            prompt = null
        )
        assertThat(url).doesNotContain("prompt=")
    }

    @Test
    fun `redirect uri uses animerec custom scheme and host is auth`() {
        val url = OAuthUtil.buildAuthorizationUrl("c", state = "s")
        // The url is the AUTH url, not the redirect; redirect_uri is a
        // query parameter on the auth url.
        assertThat(url).contains("redirect_uri=animerec%3A%2F%2Fauth")
    }

    @Test
    fun `extractAuthCode returns the code from a successful redirect URI`() {
        val uri = Uri.parse("animerec://auth?code=my_auth_code&state=expected_state")
        assertThat(OAuthUtil.extractAuthCode(uri)).isEqualTo("my_auth_code")
    }

    @Test
    fun `extractError returns the error from a failed redirect URI`() {
        val uri = Uri.parse("animerec://auth?error=access_denied&error_description=user+denied")
        assertThat(OAuthUtil.extractError(uri)).isEqualTo("access_denied")
    }

    @Test
    fun `extractState returns the state from the redirect URI`() {
        val uri = Uri.parse("animerec://auth?code=abc&state=cryptographically_random_state")
        assertThat(OAuthUtil.extractState(uri)).isEqualTo("cryptographically_random_state")
    }

    @Test
    fun `extractAuthCode returns null when URI is missing code parameter`() {
        val uri = Uri.parse("animerec://auth?state=s&error=denied")
        assertThat(OAuthUtil.extractAuthCode(uri)).isNull()
    }
}
