/*
 * AnimeRec - Anime Recomendación App
 * Copyright (C) 2025 Shuvam Banerji Seal
 * Licensed under the MIT License.
 */
package com.animerec.app.ui.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for [OAuthLauncher].
 *
 * **v1.1.6 simplification:** the launcher used to have a 3-strategy
 * fallback chain plus a pre-flight `hasAnyBrowser` check. Both
 * were over-engineering. v1.1.1's "just call Intent.ACTION_VIEW"
 * approach was correct; the only thing wrong with v1.1.4 was the
 * pre-flight check (which used `queryIntentActivities()` and
 * returned empty on API 30+ because of package visibility).
 *
 * v1.1.6 goes back to a single `Intent.ACTION_VIEW` call. The
 * tests verify:
 *  1. The launch invokes `startActivity` with `ACTION_VIEW` and
 *     the correct URI.
 *  2. `FLAG_ACTIVITY_NEW_TASK` is set (required for launching
 *     from a Fragment / Application context).
 *  3. `FLAG_ACTIVITY_REQUIRE_NON_BROWSER` is **not** set
 *     (regression for the v1.1.3 bug).
 *  4. If `startActivity` throws `ActivityNotFoundException`, the
 *     launcher posts a `no_browser` error to the bus.
 *  5. If `startActivity` throws `SecurityException`, the launcher
 *     posts a `security_error` to the bus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OAuthLauncherTest {

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `launch invokes startActivity with ACTION_VIEW and the auth URL`() {
        val context = makeContextWithStartActivity()
        val authUrl = "https://myanimelist.net/v1/oauth2/authorize?client_id=abc"
        val launched = OAuthLauncher.launch(context, authUrl)
        assertThat(launched).isTrue()
        verify(exactly = 1) {
            context.startActivity(match<Intent> { intent ->
                intent.action == Intent.ACTION_VIEW &&
                    intent.data?.toString() == authUrl
            })
        }
    }

    @Test
    fun `launch sets FLAG_ACTIVITY_NEW_TASK so it works from a fragment context`() {
        var capturedIntent: Intent? = null
        val context = makeContextCapturingIntent { intent ->
            capturedIntent = intent
        }
        OAuthLauncher.launch(
            context,
            "https://myanimelist.net/v1/oauth2/authorize?x"
        )
        val intent = capturedIntent
        assertThat(intent).isNotNull()
        val hasNewTask = (intent!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0
        assertThat(hasNewTask).isTrue()
    }

    @Test
    fun `launch does NOT set FLAG_ACTIVITY_REQUIRE_NON_BROWSER (v1_1_3 regression test)`() {
        // v1.1.3 set this flag, which had the opposite effect of
        // what the author intended: it filtered out all browsers,
        // causing "no browser available" on devices that had
        // Chrome installed. Verify the new code does NOT set it.
        var capturedIntent: Intent? = null
        val context = makeContextCapturingIntent { intent ->
            capturedIntent = intent
        }
        OAuthLauncher.launch(
            context,
            "https://myanimelist.net/v1/oauth2/authorize?x"
        )
        val intent = capturedIntent
        assertThat(intent).isNotNull()
        val hasBadFlag = (intent!!.flags and Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER) != 0
        assertThat(hasBadFlag).isFalse()
    }

    @Test
    fun `launch preserves the full URL in the launch intent`() {
        val authUrl = "https://myanimelist.net/v1/oauth2/authorize" +
            "?response_type=code&client_id=abc&state=xyz" +
            "&code_challenge=challenge&code_challenge_method=plain" +
            "&redirect_uri=animerec%3A%2F%2Fauth"
        var capturedIntent: Intent? = null
        val context = makeContextCapturingIntent { intent ->
            capturedIntent = intent
        }
        OAuthLauncher.launch(context, authUrl)
        assertThat(capturedIntent?.data?.toString()).isEqualTo(authUrl)
    }

    @Test
    fun `launch returns true on successful startActivity`() {
        val context = makeContextWithStartActivity()
        val result = OAuthLauncher.launch(
            context,
            "https://myanimelist.net/v1/oauth2/authorize?x"
        )
        assertThat(result).isTrue()
    }

    @Test
    fun `launch posts no_browser error when startActivity throws ActivityNotFoundException`() {
        val context = makeContextThrowing(ActivityNotFoundException("no browser"))
        val result = OAuthLauncher.launch(
            context,
            "https://myanimelist.net/v1/oauth2/authorize?x"
        )
        assertThat(result).isFalse()
        shadowOf(Looper.getMainLooper()).idle()
        val event = AuthCallbackBus.events.value
        assertThat(event).isInstanceOf(AuthCallbackEvent.Error::class.java)
        assertThat((event as AuthCallbackEvent.Error).code).isEqualTo("no_browser")
    }

    @Test
    fun `launch posts security_error when startActivity throws SecurityException`() {
        val context = makeContextThrowing(SecurityException("blocked"))
        val result = OAuthLauncher.launch(
            context,
            "https://myanimelist.net/v1/oauth2/authorize?x"
        )
        assertThat(result).isFalse()
        shadowOf(Looper.getMainLooper()).idle()
        val event = AuthCallbackBus.events.value
        assertThat(event).isInstanceOf(AuthCallbackEvent.Error::class.java)
        assertThat((event as AuthCallbackEvent.Error).code).isEqualTo("security_error")
    }

    // ---- Helpers ----

    /**
     * Build a mock [Context] whose `startActivity` accepts the
     * intent (returns null).
     */
    private fun makeContextWithStartActivity(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.startActivity(any<Intent>()) } returns Unit
        return context
    }

    /**
     * Build a mock [Context] whose `startActivity` throws the given
     * exception.
     */
    private fun makeContextThrowing(exception: Exception): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.startActivity(any<Intent>()) } throws exception
        return context
    }

    /**
     * Build a mock [Context] that captures the first launch intent
     * and hands it to [onCaptured] so the test can inspect it.
     */
    private fun makeContextCapturingIntent(onCaptured: (Intent) -> Unit): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.startActivity(any<Intent>()) } answers {
            val intent = firstArg<Intent>()
            onCaptured(intent)
        }
        return context
    }
}
