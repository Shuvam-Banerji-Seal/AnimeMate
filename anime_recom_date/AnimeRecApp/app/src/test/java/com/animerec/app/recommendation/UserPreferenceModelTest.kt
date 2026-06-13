/*
 * AnimeRec - Anime Recommendation App
 * Copyright (C) 2025 Shuvam Banerji Seal
 * Licensed under the MIT License.
 */
package com.animerec.app.recommendation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.animerec.app.models.AnimeContent
import com.animerec.app.models.ContentType
import com.animerec.app.models.User
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for UserPreferenceModel:
 *  - updatePreferencesFromInteraction applies the correct multiplier
 *  - savePreferences / loadPreferences round-trips through SharedPreferences
 *  - clearPreferences wipes everything
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserPreferenceModelTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newPrefs(): UserPreferenceModel = UserPreferenceModel(context)

    @Test
    fun `positive interaction increases genre weight`() {
        val prefs = newPrefs()
        val content = AnimeContent(
            id = 1,
            title = "X",
            type = ContentType.ANIME,
            genres = listOf("Action")
        )
        prefs.updatePreferencesFromInteraction(content, isPositive = true, weight = 1.0)
        assertThat(prefs.getWeight("Action")).isAtLeast(1.0)
    }

    @Test
    fun `negative interaction decreases genre weight`() {
        val prefs = newPrefs()
        val content = AnimeContent(
            id = 1,
            title = "X",
            type = ContentType.ANIME,
            genres = listOf("Comedy")
        )
        prefs.updatePreferencesFromInteraction(content, isPositive = false, weight = 1.0)
        assertThat(prefs.getWeight("Comedy")).isAtMost(-0.5)
    }

    @Test
    fun `weight is clamped to -10 to +10 range`() {
        val prefs = newPrefs()
        val content = AnimeContent(
            id = 1,
            title = "X",
            type = ContentType.ANIME,
            genres = listOf("Drama")
        )
        // 100 strong positive likes must clamp at +10
        repeat(100) { prefs.updatePreferencesFromInteraction(content, isPositive = true, weight = 1.0) }
        assertThat(prefs.getWeight("Drama")).isAtMost(10.0)
        // 100 strong negative must clamp at -10
        repeat(100) { prefs.updatePreferencesFromInteraction(content, isPositive = false, weight = 1.0) }
        assertThat(prefs.getWeight("Drama")).isAtLeast(-10.0)
    }

    @Test
    fun `getTopGenres returns only positive weights sorted descending`() {
        val prefs = newPrefs()
        val c1 = AnimeContent(id = 1, title = "X", type = ContentType.ANIME, genres = listOf("Action"))
        val c2 = AnimeContent(id = 2, title = "Y", type = ContentType.ANIME, genres = listOf("Comedy"))
        val c3 = AnimeContent(id = 3, title = "Z", type = ContentType.ANIME, genres = listOf("Drama"))
        // Like Comedy once, Action twice, Drama disliked once
        prefs.updatePreferencesFromInteraction(c1, true, 1.0)
        prefs.updatePreferencesFromInteraction(c1, true, 1.0)
        prefs.updatePreferencesFromInteraction(c2, true, 1.0)
        prefs.updatePreferencesFromInteraction(c3, false, 1.0)
        val top = prefs.getTopGenres(10)
        // Action (2) > Comedy (1); Drama is negative so excluded
        assertThat(top).containsExactly("Action", "Comedy").inOrder()
    }

    @Test
    fun `getDislikedGenres returns only weights under threshold`() {
        val prefs = newPrefs()
        val c1 = AnimeContent(id = 1, title = "X", type = ContentType.ANIME, genres = listOf("Horror"))
        val c2 = AnimeContent(id = 2, title = "Y", type = ContentType.ANIME, genres = listOf("Slice"))
        // -2.5 per like for negative (weight * 0.5 = 0.5, multiplied by -1 → -0.5).
        // Need multiple negatives to pass the -2 threshold.
        repeat(10) { prefs.updatePreferencesFromInteraction(c1, false, 1.0) }
        // +1 weight for Slice
        prefs.updatePreferencesFromInteraction(c2, true, 1.0)
        val disliked = prefs.getDislikedGenres()
        assertThat(disliked).contains("Horror")
        assertThat(disliked).doesNotContain("Slice")
    }

    @Test
    fun `preferences persist across instances`() {
        val prefs1 = newPrefs()
        val content = AnimeContent(
            id = 1,
            title = "X",
            type = ContentType.ANIME,
            genres = listOf("Mystery")
        )
        prefs1.updatePreferencesFromInteraction(content, true, 1.0)
        // Re-construct to force a fresh load from SharedPreferences
        val prefs2 = newPrefs()
        assertThat(prefs2.getWeight("Mystery")).isAtLeast(1.0)
    }

    @Test
    fun `clearPreferences wipes everything`() {
        val prefs = newPrefs()
        val content = AnimeContent(id = 1, title = "X", type = ContentType.ANIME, genres = listOf("Mystery"))
        prefs.updatePreferencesFromInteraction(content, true, 1.0)
        assertThat(prefs.getWeight("Mystery")).isAtLeast(1.0)
        prefs.clearPreferences()
        assertThat(prefs.getWeight("Mystery")).isEqualTo(0.0)
    }

    @Test
    fun `recommendation score includes explicit genre preference bonus`() {
        val prefs = newPrefs()
        val user = User(
            id = 1,
            name = "X",
            genrePreferences = listOf("Sci-Fi")
        )
        val content = AnimeContent(
            id = 1,
            title = "X",
            type = ContentType.ANIME,
            genres = listOf("Sci-Fi")
        )
        prefs.updatePreferencesFromInteraction(content, true, 1.0)
        // With a +1 learned weight and Sci-Fi in explicit prefs, score must include both
        val score = prefs.scoreForTest(content, user)
        assertThat(score).isAtLeast(3.0) // 1 (learned) + 2 (explicit) + 7/5 (MAL bonus)
    }
}
