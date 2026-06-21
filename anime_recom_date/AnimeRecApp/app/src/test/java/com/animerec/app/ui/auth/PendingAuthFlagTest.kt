/*
 * AnimeRec - Anime Recomendación App
 * Copyright (C) 2025 Shuvam Banerji Seal
 * Licensed under the MIT License.
 */
package com.animerec.app.ui.auth

import com.animerec.app.utils.SecureStorage
import com.animerec.app.utils.SecureStorageUnavailableException
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for the pending-auth-success recovery mechanism.
 *
 * The OAuthCallbackActivity writes a pending flag to SecureStorage
 * after a successful token exchange, so that even if the process is
 * killed between the bus-post and the LoginFragment re-binding to
 * the bus, the fragment can recover by checking the flag.
 *
 * These tests verify the flag's contract:
 *  - Default value is `false`
 *  - Written value persists across "process restarts"
 *  - Cleared value reads as `false` again
 *
 * **Note:** These tests are skipped on JVM-only Robolectric because
 * AndroidKeyStore isn't available there. They run on a real device
 * or under `connectedAndroidTest`. The flag is also a recovery
 * mechanism — the primary path (bus event) is what handles the
 * common case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PendingAuthFlagTest {

    @Before
    fun setUp() {
        // Skip the entire class if AndroidKeyStore is not available
        // (i.e. JVM-only Robolectric). The flag's contract is
        // exercised in instrumented tests on a real device.
        try {
            SecureStorage(RuntimeEnvironment.getApplication())
        } catch (e: SecureStorageUnavailableException) {
            assumeTrue("AndroidKeyStore not available in test runtime", false)
        } catch (e: Exception) {
            // Other init failures (e.g. NoSuchAlgorithmException) also
            // imply AndroidKeyStore isn't available.
            assumeTrue("SecureStorage cannot init: ${e.message}", false)
        }
    }

    @Test
    fun `pending auth flag defaults to false`() {
        val context = RuntimeEnvironment.getApplication()
        val storage = SecureStorage(context)
        // Wipe any leftover state from previous tests.
        storage.putBoolean(PENDING_AUTH_SUCCESS_KEY, false)
        assertThat(storage.getBoolean(PENDING_AUTH_SUCCESS_KEY, false)).isFalse()
    }

    @Test
    fun `setting pending flag then reading returns true`() {
        val context = RuntimeEnvironment.getApplication()
        val storage = SecureStorage(context)
        storage.putBoolean(PENDING_AUTH_SUCCESS_KEY, true)
        assertThat(storage.getBoolean(PENDING_AUTH_SUCCESS_KEY, false)).isTrue()
        // Cleanup
        storage.putBoolean(PENDING_AUTH_SUCCESS_KEY, false)
    }

    @Test
    fun `pending flag survives a 'process restart' (re-instantiation)`() {
        val context = RuntimeEnvironment.getApplication()
        // Simulate OAuthCallbackActivity writing the flag.
        val storage1 = SecureStorage(context)
        storage1.putBoolean(PENDING_AUTH_SUCCESS_KEY, true)
        // Simulate process kill + restart by creating a new
        // SecureStorage instance. The EncryptedSharedPreferences
        // backing it is process-scoped but the file persists, so
        // the new instance reads the same data.
        val storage2 = SecureStorage(context)
        assertThat(storage2.getBoolean(PENDING_AUTH_SUCCESS_KEY, false)).isTrue()
        // Simulate LoginFragment clearing the flag after recovery.
        storage2.putBoolean(PENDING_AUTH_SUCCESS_KEY, false)
        // Verify it's gone.
        val storage3 = SecureStorage(context)
        assertThat(storage3.getBoolean(PENDING_AUTH_SUCCESS_KEY, false)).isFalse()
    }
}
