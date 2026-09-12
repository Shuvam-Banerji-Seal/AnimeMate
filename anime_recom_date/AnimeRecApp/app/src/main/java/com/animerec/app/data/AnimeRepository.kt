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
package com.animerec.app.data

import com.animerec.app.models.AnimeContent
import com.animerec.app.models.ContentType
import com.animerec.app.models.User

/**
 * Repository interface for anime data operations
 */
interface AnimeRepository {
    // User operations
    suspend fun getUserProfile(): Resource<User>
    fun isProfileComplete(): Boolean
    
    // Content retrieval
    suspend fun getRecommendations(limit: Int): Resource<List<AnimeContent>>
    suspend fun searchAnime(query: String, limit: Int = 25): Resource<List<AnimeContent>>
    suspend fun searchManga(query: String, limit: Int = 25): Resource<List<AnimeContent>>
    suspend fun getAnimeDetails(id: Int): Resource<AnimeContent>
    suspend fun getMangaDetails(id: Int): Resource<AnimeContent>
    
    // Recommendation-specific methods
    suspend fun getAnimeRecommendations(genres: List<String>, limit: Int, rankingType: String = "all"): Resource<List<AnimeContent>>
    suspend fun getMangaRecommendations(genres: List<String>, limit: Int, rankingType: String = "all"): Resource<List<AnimeContent>>
    suspend fun getNovelRecommendations(genres: List<String>, limit: Int, rankingType: String = "novels"): Resource<List<AnimeContent>>
    suspend fun getAnimeRankings(rankingType: String, limit: Int): Resource<List<AnimeContent>>
    suspend fun getSeasonalAnime(year: Int, season: String, limit: Int): Resource<List<AnimeContent>>
    
    // User list operations
    suspend fun getUserAnimeList(status: String? = null): Resource<List<AnimeContent>>
    suspend fun getUserMangaList(status: String? = null): Resource<List<AnimeContent>>
    suspend fun updateAnimeStatus(animeId: Int, status: String): Resource<Boolean>
    suspend fun updateMangaStatus(mangaId: Int, status: String): Resource<Boolean>

    /**
     * Remove an item from the user's MAL list entirely.
     *
     * Used to undo an accidental swipe. MAL answers 404 when the entry isn't
     * on the list; that's treated as success, since the desired end state
     * ("not on the list") already holds.
     */
    suspend fun removeAnimeFromList(animeId: Int): Resource<Boolean>
    suspend fun removeMangaFromList(mangaId: Int): Resource<Boolean>
    
    // Convenience methods for ViewModels
    suspend fun getAnimeWatchlist(): Resource<List<AnimeContent>>
    suspend fun getMangaWatchlist(): Resource<List<AnimeContent>>
    suspend fun getNovelWatchlist(): Resource<List<AnimeContent>>
    suspend fun getAnimeList(status: String): Resource<List<AnimeContent>>
    suspend fun getMangaList(status: String): Resource<List<AnimeContent>>
    suspend fun getNovelList(status: String): Resource<List<AnimeContent>>
    suspend fun rateAnime(animeId: Int, score: Int): Resource<Boolean>
    suspend fun rateManga(mangaId: Int, score: Int): Resource<Boolean>
    
    // Not interested tracking
    /**
     * Record that the user never wants to see this item again.
     *
     * [type] is required so the entry can be stored in the right ID namespace —
     * MAL numbers anime and manga separately, so an untyped ID would also hide
     * the unrelated work that shares its number in the other namespace.
     */
    suspend fun markAsNotInterested(contentId: Int, type: ContentType): Resource<Boolean>

    /** Undo a [markAsNotInterested] — used when the user reverses a left-swipe. */
    suspend fun unmarkAsNotInterested(contentId: Int, type: ContentType): Resource<Boolean>

    /** Not-interested entries, split into namespaced keys and pre-migration untyped IDs. */
    suspend fun getNotInterestedContentKeys(): Resource<NotInterestedKeys>
    
    // Cache management
    fun clearCache()
}

/**
 * Not-interested entries.
 *
 * @property typedKeys namespaced [AnimeContent.contentKey] values, e.g. "a:1535".
 * @property legacyIds bare IDs recorded before entries carried a content type.
 *   Their namespace is unknown, so callers match them against both.
 */
data class NotInterestedKeys(
    val typedKeys: Set<String> = emptySet(),
    val legacyIds: Set<Int> = emptySet()
)
