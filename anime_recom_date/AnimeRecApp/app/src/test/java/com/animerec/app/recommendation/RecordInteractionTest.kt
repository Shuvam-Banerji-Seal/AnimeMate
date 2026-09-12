/*
 * AnimeRec - Anime Recommendation App
 * Copyright (C) 2025 Shuvam Banerji Seal
 * Licensed under the MIT License.
 */
package com.animerec.app.recommendation

import com.animerec.app.data.AnimeRepository
import com.animerec.app.data.NotInterestedKeys
import com.animerec.app.data.Resource
import com.animerec.app.models.AnimeContent
import com.animerec.app.models.ContentType
import com.animerec.app.models.User
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Regression tests for the interaction-recording bugs fixed in 1.2.0.
 *
 * Before: `recordInteraction(contentId, type)` re-fetched the item with
 * `repository.getAnimeDetails(contentId)` regardless of what kind of content
 * the ID belonged to, then wrote a MAL list status based on the result. For a
 * manga that meant training the preference model on — and adding to the user's
 * anime list — whatever unrelated anime happened to share the manga's number.
 * It also duplicated a list write every caller was already performing.
 */
class RecordInteractionTest {

    private fun engine(repo: AnimeRepository, prefs: UserPreferenceModel) =
        BasicRecommendationEngine(repo, prefs)

    private val manga = AnimeContent(
        id = 1535,
        title = "Boys Next Door",
        type = ContentType.MANGA,
        genres = listOf("Drama")
    )

    private val anime = AnimeContent(
        id = 1535,
        title = "Death Note",
        type = ContentType.ANIME,
        genres = listOf("Mystery")
    )

    @Test
    fun `recording a like does not re-fetch the item by id`() = runBlocking<Unit> {
        val repo = mockk<AnimeRepository>(relaxed = true)
        val prefs = mockk<UserPreferenceModel>(relaxed = true)

        engine(repo, prefs).recordInteraction(manga, RecommendationEngine.InteractionType.LIKE)

        // The wrong-namespace lookup is gone entirely.
        coVerify(exactly = 0) { repo.getAnimeDetails(any()) }
        coVerify(exactly = 0) { repo.getMangaDetails(any()) }
    }

    @Test
    fun `recording a like does not write to the MAL list`() = runBlocking<Unit> {
        // Callers own their own status writes; the engine doing it too meant
        // two PATCH requests per swipe.
        val repo = mockk<AnimeRepository>(relaxed = true)
        val prefs = mockk<UserPreferenceModel>(relaxed = true)

        engine(repo, prefs).recordInteraction(anime, RecommendationEngine.InteractionType.LIKE)

        coVerify(exactly = 0) { repo.updateAnimeStatus(any(), any()) }
        coVerify(exactly = 0) { repo.updateMangaStatus(any(), any()) }
    }

    @Test
    fun `a manga interaction trains the preference model on the manga itself`() = runBlocking<Unit> {
        val repo = mockk<AnimeRepository>(relaxed = true)
        val prefs = mockk<UserPreferenceModel>(relaxed = true)

        engine(repo, prefs).recordInteraction(manga, RecommendationEngine.InteractionType.LIKE)

        // Exactly the item we passed in — not an anime that shares its ID.
        verify { prefs.updatePreferencesFromInteraction(manga, true) }
    }

    @Test
    fun `a dislike does not write not-interested itself`() = runBlocking<Unit> {
        val repo = mockk<AnimeRepository>(relaxed = true)
        val prefs = mockk<UserPreferenceModel>(relaxed = true)

        engine(repo, prefs).recordInteraction(anime, RecommendationEngine.InteractionType.DISLIKE)

        coVerify(exactly = 0) { repo.markAsNotInterested(any(), any()) }
        verify { prefs.updatePreferencesFromInteraction(anime, false) }
    }

    @Test
    fun `a super like is weighted more heavily than a like`() = runBlocking<Unit> {
        val repo = mockk<AnimeRepository>(relaxed = true)
        val prefs = mockk<UserPreferenceModel>(relaxed = true)

        engine(repo, prefs).recordInteraction(anime, RecommendationEngine.InteractionType.SUPER_LIKE)

        verify { prefs.updatePreferencesFromInteraction(anime, true, 2.0) }
    }

    @Test
    fun `similar content for a manga uses the manga endpoint`() = runBlocking<Unit> {
        val repo = mockk<AnimeRepository>(relaxed = true)
        val prefs = mockk<UserPreferenceModel>(relaxed = true)
        coEvery { repo.getMangaDetails(1535) } returns Resource.Success(manga)
        coEvery { repo.getMangaRecommendations(any(), any(), any()) } returns Resource.Success(emptyList())

        engine(repo, prefs).getSimilarContent(1535, ContentType.MANGA, 10)

        coVerify(exactly = 1) { repo.getMangaDetails(1535) }
        coVerify(exactly = 0) { repo.getAnimeDetails(1535) }
    }

    @Test
    fun `similar content for an anime uses the anime endpoint`() = runBlocking<Unit> {
        val repo = mockk<AnimeRepository>(relaxed = true)
        val prefs = mockk<UserPreferenceModel>(relaxed = true)
        coEvery { repo.getAnimeDetails(1535) } returns Resource.Success(anime)
        coEvery { repo.getAnimeRecommendations(any(), any(), any()) } returns Resource.Success(emptyList())

        engine(repo, prefs).getSimilarContent(1535, ContentType.ANIME, 10)

        coVerify(exactly = 1) { repo.getAnimeDetails(1535) }
        coVerify(exactly = 0) { repo.getMangaDetails(1535) }
    }

    @Test
    fun `a manga on the read list does not exclude an anime with the same id`() = runBlocking<Unit> {
        // The exclusion set used to be a flat Set<Int>, so every ID on the
        // user's manga list also blocked the unrelated anime with that number.
        val repo = mockk<AnimeRepository>(relaxed = true)
        val prefs = mockk<UserPreferenceModel>(relaxed = true)

        val animeCandidates = (1..5).map {
            AnimeContent(id = it, title = "Anime $it", type = ContentType.ANIME, genres = listOf("Action"))
        }
        coEvery { repo.getAnimeRecommendations(any(), any(), any()) } returns Resource.Success(animeCandidates)
        coEvery { repo.getUserAnimeList(null) } returns Resource.Success(emptyList())
        // The user has read manga 1, 2 and 3 — different works from anime 1-3.
        coEvery { repo.getUserMangaList(null) } returns Resource.Success(
            (1..3).map { AnimeContent(id = it, title = "Manga $it", type = ContentType.MANGA) }
        )
        coEvery { repo.getNotInterestedContentKeys() } returns Resource.Success(NotInterestedKeys())

        val user = User(id = 1, name = "test", contentPreferences = listOf("anime"))
        val recs = engine(repo, prefs).getRecommendations(user, 20)

        val returned = (recs as Resource.Success).data
        assertThat(returned.map { it.contentKey }).containsAtLeast("a:1", "a:2", "a:3")
    }

    @Test
    fun `an anime on the watch list is still excluded`() = runBlocking<Unit> {
        val repo = mockk<AnimeRepository>(relaxed = true)
        val prefs = mockk<UserPreferenceModel>(relaxed = true)

        val animeCandidates = (1..5).map {
            AnimeContent(id = it, title = "Anime $it", type = ContentType.ANIME, genres = listOf("Action"))
        }
        coEvery { repo.getAnimeRecommendations(any(), any(), any()) } returns Resource.Success(animeCandidates)
        coEvery { repo.getUserAnimeList(null) } returns Resource.Success(
            (1..3).map { AnimeContent(id = it, title = "Anime $it", type = ContentType.ANIME) }
        )
        coEvery { repo.getUserMangaList(null) } returns Resource.Success(emptyList())
        coEvery { repo.getNotInterestedContentKeys() } returns Resource.Success(NotInterestedKeys())

        val user = User(id = 1, name = "test", contentPreferences = listOf("anime"))
        val recs = engine(repo, prefs).getRecommendations(user, 20)

        val returned = (recs as Resource.Success).data
        assertThat(returned.map { it.contentKey }).containsNoneOf("a:1", "a:2", "a:3")
    }

    @Test
    fun `legacy untyped not-interested ids are still honoured in both namespaces`() = runBlocking<Unit> {
        // Entries written before the app stored a content type can't be
        // assigned a namespace retroactively, so they keep their original
        // (over-broad) behaviour rather than being silently dropped.
        val repo = mockk<AnimeRepository>(relaxed = true)
        val prefs = mockk<UserPreferenceModel>(relaxed = true)

        val animeCandidates = (1..5).map {
            AnimeContent(id = it, title = "Anime $it", type = ContentType.ANIME, genres = listOf("Action"))
        }
        coEvery { repo.getAnimeRecommendations(any(), any(), any()) } returns Resource.Success(animeCandidates)
        coEvery { repo.getUserAnimeList(null) } returns Resource.Success(emptyList())
        coEvery { repo.getUserMangaList(null) } returns Resource.Success(emptyList())
        coEvery { repo.getNotInterestedContentKeys() } returns Resource.Success(
            NotInterestedKeys(typedKeys = emptySet(), legacyIds = setOf(2, 4))
        )

        val user = User(id = 1, name = "test", contentPreferences = listOf("anime"))
        val recs = engine(repo, prefs).getRecommendations(user, 20)

        val returned = (recs as Resource.Success).data
        assertThat(returned.map { it.contentKey }).containsNoneOf("a:2", "a:4")
    }

    @Test
    fun `typed not-interested keys only exclude their own namespace`() = runBlocking<Unit> {
        val repo = mockk<AnimeRepository>(relaxed = true)
        val prefs = mockk<UserPreferenceModel>(relaxed = true)

        val animeCandidates = (1..5).map {
            AnimeContent(id = it, title = "Anime $it", type = ContentType.ANIME, genres = listOf("Action"))
        }
        coEvery { repo.getAnimeRecommendations(any(), any(), any()) } returns Resource.Success(animeCandidates)
        coEvery { repo.getUserAnimeList(null) } returns Resource.Success(emptyList())
        coEvery { repo.getUserMangaList(null) } returns Resource.Success(emptyList())
        // Rejected the *manga* with ID 2 — the anime with ID 2 is untouched.
        coEvery { repo.getNotInterestedContentKeys() } returns Resource.Success(
            NotInterestedKeys(typedKeys = setOf("m:2"))
        )

        val user = User(id = 1, name = "test", contentPreferences = listOf("anime"))
        val recs = engine(repo, prefs).getRecommendations(user, 20)

        val returned = (recs as Resource.Success).data
        assertThat(returned.map { it.contentKey }).contains("a:2")
    }
}
