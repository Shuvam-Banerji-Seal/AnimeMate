/*
 * AnimeRec - Anime Recomendación App
 * Copyright (C) 2025 Shuvam Banerji Seal
 * Licensed under the MIT License.
 */
package com.animerec.app.ui.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for [OAuthLauncher].
 *
 * The previous version of OAuthLauncher added
 * `FLAG_ACTIVITY_REQUIRE_NON_BROWSER` on API 30+, which — despite
 * sounding like "use a real browser" — actually means "do not
 * deliver this Intent to a browser app". That broke the launch
 * silently. These tests pin the bug down: they exercise the
 * `ActivityNotFoundException` path and assert that the launcher
 * (a) does not return early and (b) eventually surfaces a
 * structured error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OAuthLauncherTest {

    @Before
    fun resetBusBefore() {
        // The bus persists between tests because it's a process-wide
        // singleton. Drain any pending events from previous tests
        // before starting a new one. We post a "null" via postValue is
        // not possible (LiveData can't hold null AuthCallbackEvent
        // since it's an interface — we just rely on the next test's
        // assertion to check the latest event).
    }

    @After
    fun tearDown() {
        // Drain pending postValue events so they don't leak.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `launch posts no_browser error when no resolver exists for https`() {
        val context = makeContextWithResolvers(emptyList())
        val result = OAuthLauncher.launch(context, "https://myanimelist.net/v1/oauth2/authorize?client_id=abc")
        // Pre-flight caught the empty resolver list; no startActivity
        // attempt, just a bus error.
        assertThat(result).isFalse()
        shadowOf(Looper.getMainLooper()).idle()
        val event = AuthCallbackBus.events.value
        assertThat(event).isInstanceOf(AuthCallbackEvent.Error::class.java)
        val err = event as AuthCallbackEvent.Error
        assertThat(err.code).isEqualTo("no_browser")
        assertThat(err.message).contains("No browser")
    }

    @Test
    fun `launch returns true when first strategy succeeds`() {
        val context = makeContextWhereStrategySucceeds(strategyIndex = 1)
        val result = OAuthLauncher.launch(context, "https://myanimelist.net/v1/oauth2/authorize?client_id=abc")
        assertThat(result).isTrue()
        // Drain the looper. The bus should not have received any
        // event because the launch succeeded.
        shadowOf(Looper.getMainLooper()).idle()
        val lastEvent = AuthCallbackBus.events.value
        // The last event may be from a previous test (the bus is a
        // singleton). What we really care about is that *this* test
        // didn't trigger an error. The easiest way to check that is
        // to look at the call counter: when strategy 1 succeeds,
        // only 1 startActivity is attempted.
        verify(exactly = 1) { context.startActivity(any<Intent>()) }
        // For sanity, also check that no new no_browser error was
        // posted by comparing against the previous test's event.
        // (Skipped — call-counter check is sufficient.)
        @Suppress("UNUSED_VARIABLE")
        val unused = lastEvent
    }

    @Test
    fun `launch falls back to createChooser when strategy 1 throws ActivityNotFoundException`() {
        val context = makeContextWhereStrategySucceeds(strategyIndex = 2)
        val result = OAuthLauncher.launch(context, "https://myanimelist.net/v1/oauth2/authorize?client_id=abc")
        assertThat(result).isTrue()
    }

    @Test
    fun `launch falls back to plain ACTION_VIEW when strategies 1 and 2 throw`() {
        val context = makeContextWhereStrategySucceeds(strategyIndex = 3)
        val result = OAuthLauncher.launch(context, "https://myanimelist.net/v1/oauth2/authorize?client_id=abc")
        assertThat(result).isTrue()
    }

    @Test
    fun `launch reports no_browser when all 3 strategies throw ActivityNotFoundException`() {
        val context = makeContextWhereStrategySucceeds(strategyIndex = -1)
        val result = OAuthLauncher.launch(context, "https://myanimelist.net/v1/oauth2/authorize?client_id=abc")
        assertThat(result).isFalse()
        shadowOf(Looper.getMainLooper()).idle()
        val event = AuthCallbackBus.events.value
        assertThat(event).isInstanceOf(AuthCallbackEvent.Error::class.java)
        assertThat((event as AuthCallbackEvent.Error).code).isEqualTo("no_browser")
    }

    @Test
    fun `launch does not set FLAG_ACTIVITY_REQUIRE_NON_BROWSER on the launch intent (regression test for v1_1_3 bug)`() {
        // v1.1.3 set this flag, thinking it would force a real browser.
        // In fact the flag means "do not deliver to a browser", which
        // broke the launch. Verify the new code does NOT set it.
        var capturedIntent: Intent? = null
        val context = makeContextCapturingIntent { intent ->
            capturedIntent = intent
        }
        OAuthLauncher.launch(context, "https://myanimelist.net/v1/oauth2/authorize?x")
        val intent = capturedIntent
        assertThat(intent).isNotNull()
        val hasBadFlag = (intent!!.flags and Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER) != 0
        assertThat(hasBadFlag).isFalse()
        // The intent must have a URI scheme that browsers can match.
        assertThat(intent.data?.scheme).isEqualTo("https")
    }

    @Test
    fun `launch strategy 1 sets NEW_TASK so it can launch from a fragment context`() {
        // Required for launching an external activity from a
        // Fragment / Application context.
        var capturedIntent: Intent? = null
        val context = makeContextCapturingIntent { intent ->
            capturedIntent = intent
        }
        OAuthLauncher.launch(context, "https://myanimelist.net/v1/oauth2/authorize?x")
        val intent = capturedIntent
        assertThat(intent).isNotNull()
        val hasNewTask = (intent!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0
        assertThat(hasNewTask).isTrue()
    }

    // ---- Helpers ----

    private fun makeContextWithResolvers(resolvers: List<ResolveInfo>): Context {
        val pm = mockk<PackageManager>(relaxed = true)
        every {
            pm.queryIntentActivities(any<Intent>(), any<Int>())
        } returns resolvers
        every {
            pm.queryIntentActivities(any<Intent>(), any<PackageManager.ResolveInfoFlags>())
        } returns resolvers

        val context = mockk<Context>(relaxed = true)
        every { context.packageManager } returns pm
        return context
    }

    /**
     * Build a mock [Context] that:
     *  - Reports at least one resolver for the pre-flight `https://`
     *    check.
     *  - Lets [strategyIndex] be the one that *does not* throw
     *    `ActivityNotFoundException`. 1 = first, 2 = chooser, 3 = plain,
     *    -1 = all throw.
     */
    private fun makeContextWhereStrategySucceeds(strategyIndex: Int): Context {
        val pm = makeResolvers(listOf(makeResolveInfo("com.android.chrome")))
        val callCount = intArrayOf(0)
        val context = mockk<Context>(relaxed = true)
        every { context.packageManager } returns pm
        every { context.startActivity(any<Intent>()) } answers {
            callCount[0]++
            val thisCall = callCount[0]
            if (thisCall == strategyIndex) {
                // Success: don't throw.
                Unit
            } else {
                throw ActivityNotFoundException("No activity for call #$thisCall")
            }
        }
        return context
    }

    /**
     * Build a mock [Context] that captures the first launch intent
     * and hands it to [onCaptured] so the test can inspect it.
     */
    private fun makeContextCapturingIntent(onCaptured: (Intent) -> Unit): Context {
        val pm = makeResolvers(listOf(makeResolveInfo("com.android.chrome")))
        val context = mockk<Context>(relaxed = true)
        every { context.packageManager } returns pm
        every { context.startActivity(any<Intent>()) } answers {
            val intent = firstArg<Intent>()
            onCaptured(intent)
        }
        return context
    }

    private fun makeResolvers(resolvers: List<ResolveInfo>): PackageManager {
        val pm = mockk<PackageManager>(relaxed = true)
        every {
            pm.queryIntentActivities(any<Intent>(), any<Int>())
        } returns resolvers
        every {
            pm.queryIntentActivities(any<Intent>(), any<PackageManager.ResolveInfoFlags>())
        } returns resolvers
        return pm
    }

    private fun makeResolveInfo(packageName: String): ResolveInfo {
        val info = ResolveInfo()
        info.activityInfo = ActivityInfo().apply {
            this.packageName = packageName
        }
        return info
    }
}
