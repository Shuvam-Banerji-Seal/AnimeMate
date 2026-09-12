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

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.animerec.app.AnimeRecApp
import com.animerec.app.api.MyAnimeListClient
import com.animerec.app.models.AnimeContent
import com.animerec.app.models.AnimeStatistics
import com.animerec.app.models.ContentType
import com.animerec.app.models.User
import com.animerec.app.util.ErrorLogManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Implementation of AnimeRepository
 */
class AnimeRepositoryImpl(
    private val context: Context,
    private val apiClient: MyAnimeListClient
) : AnimeRepository {
    
    private val TAG = "AnimeRepositoryImpl"
    private val prefs: SharedPreferences = context.getSharedPreferences("anime_repo_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cache = ApiResponseCache()
    
    // Cache expiration times
    private val CACHE_PROFILE = 5 * 60 * 1000L // 5 minutes
    private val CACHE_RANKINGS = 30 * 60 * 1000L // 30 minutes
    private val CACHE_DETAILS = 60 * 60 * 1000L // 1 hour
    
    override suspend fun getUserProfile(): Resource<User> {
        return try {
            val response = ErrorLogManager.logTimed(TAG, "API", "getUserProfile") {
                apiClient.service.getUserProfile(
                    fields = "id,name,gender,location,picture,anime_statistics"
                )
            }
            
            if (response.isSuccessful && response.body() != null) {
                val profileResponse = response.body()!!
                val user = User(
                    id = profileResponse.id,
                    name = profileResponse.name,
                    picture = profileResponse.picture ?: "",
                    gender = profileResponse.gender ?: "",
                    location = profileResponse.location ?: "",
                    animeStatistics = profileResponse.animeStatistics?.let {
                        AnimeStatistics(
                            numItemsWatching = it.numItemsWatching ?: 0,
                            numItemsCompleted = it.numItemsCompleted ?: 0,
                            numItemsOnHold = it.numItemsOnHold ?: 0,
                            numItemsDropped = it.numItemsDropped ?: 0,
                            numItemsPlanToWatch = it.numItemsPlanToWatch ?: 0,
                            numItems = it.numItems ?: 0,
                            numDaysWatched = it.numDaysWatched ?: 0.0,
                            meanScore = it.meanScore ?: 0.0,
                            numEpisodes = it.numEpisodes ?: 0
                        )
                    } ?: AnimeStatistics(),
                    contentPreferences = loadContentPreferences(),
                    genrePreferences = loadGenrePreferences(),
                    isProfileComplete = isProfileComplete()
                )
                Resource.Success(user)
            } else {
                Log.e(TAG, "Error getting user profile: ${response.code()}")
                ErrorLogManager.logEvent(TAG, "ERROR", "Profile API ${response.code()}: ${response.message()}")
                Resource.Error("Failed to load profile: ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting user profile", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Profile exception: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override fun isProfileComplete(): Boolean {
        return prefs.getBoolean("profile_complete", false)
    }
    
    override suspend fun getRecommendations(limit: Int): Resource<List<AnimeContent>> {
        return try {
            val response = apiClient.service.getAnimeRecommendations(
                limit = limit,
                fields = AnimeRecApp.ANIME_LIST_FIELDS
            )
            
            if (response.isSuccessful && response.body() != null) {
                val animeList = response.body()!!.data.map { it.toAnimeContent() }
                Resource.Success(animeList)
            } else {
                Resource.Error("Failed to load recommendations")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recommendations", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Recommendations API: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun searchAnime(query: String, limit: Int): Resource<List<AnimeContent>> {
        return try {
            val response = apiClient.service.searchAnime(
                query = query,
                limit = limit,
                fields = AnimeRecApp.ANIME_LIST_FIELDS
            )
            
            if (response.isSuccessful && response.body() != null) {
                val animeList = response.body()!!.data.map { it.toAnimeContent() }
                Resource.Success(animeList)
            } else {
                Resource.Error("Failed to search anime")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching anime", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Search anime: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun searchManga(query: String, limit: Int): Resource<List<AnimeContent>> {
        return try {
            val response = apiClient.service.searchManga(
                query = query,
                limit = limit,
                fields = AnimeRecApp.MANGA_LIST_FIELDS
            )
            
            if (response.isSuccessful && response.body() != null) {
                val mangaList = response.body()!!.data.map { it.toAnimeContent(ContentType.MANGA) }
                Resource.Success(mangaList)
            } else {
                Resource.Error("Failed to search manga")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching manga", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Search manga: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun getAnimeDetails(id: Int): Resource<AnimeContent> {
        return try {
            val response = apiClient.service.getAnimeDetails(
                animeId = id,
                fields = AnimeRecApp.ANIME_DETAIL_FIELDS
            )
            
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!.toAnimeContent())
            } else {
                Resource.Error("Failed to get anime details")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting anime details", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Anime details: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun getMangaDetails(id: Int): Resource<AnimeContent> {
        return try {
            val response = apiClient.service.getMangaDetails(
                mangaId = id,
                fields = AnimeRecApp.MANGA_DETAIL_FIELDS
            )
            
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!.toAnimeContent(ContentType.MANGA))
            } else {
                Resource.Error("Failed to get manga details")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting manga details", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Manga details: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun getAnimeRecommendations(genres: List<String>, limit: Int, rankingType: String): Resource<List<AnimeContent>> {
        return try {
            val response = ErrorLogManager.logTimed(TAG, "API", "getAnimeRankings($rankingType, limit=$limit)") {
                apiClient.service.getAnimeRankings(
                    rankingType = rankingType,
                    limit = limit * 2,
                    fields = AnimeRecApp.ANIME_LIST_FIELDS
                )
            }
            
            if (response.isSuccessful && response.body() != null) {
                val allAnime = response.body()!!.data.map { it.node.toAnimeContent() }
                // Filter by genres if provided
                val filtered = if (genres.isNotEmpty()) {
                    allAnime.filter { anime -> 
                        anime.genres.any { it in genres }
                    }.take(limit)
                } else {
                    allAnime.take(limit)
                }
                Resource.Success(filtered)
            } else {
                Resource.Error("Failed to get anime recommendations")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting anime recommendations", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Anime recommendations: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun getMangaRecommendations(genres: List<String>, limit: Int, rankingType: String): Resource<List<AnimeContent>> {
        return try {
            val response = apiClient.service.getMangaRankings(
                rankingType = rankingType,
                limit = limit * 2,
                fields = AnimeRecApp.MANGA_LIST_FIELDS
            )
            
            if (response.isSuccessful && response.body() != null) {
                val allManga = response.body()!!.data.map { it.node.toAnimeContent(ContentType.MANGA) }
                val filtered = if (genres.isNotEmpty()) {
                    allManga.filter { manga -> 
                        manga.genres.any { it in genres }
                    }.take(limit)
                } else {
                    allManga.take(limit)
                }
                Resource.Success(filtered)
            } else {
                Resource.Error("Failed to get manga recommendations")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting manga recommendations", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Manga recommendations: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun getNovelRecommendations(genres: List<String>, limit: Int, rankingType: String): Resource<List<AnimeContent>> {
        return try {
            // Novels are typically "lightnovel" type in MAL manga rankings
            val response = apiClient.service.getMangaRankings(
                rankingType = rankingType,
                limit = limit * 2,
                fields = AnimeRecApp.MANGA_LIST_FIELDS
            )
            
            if (response.isSuccessful && response.body() != null) {
                val allNovels = response.body()!!.data.map { it.node.toAnimeContent(ContentType.NOVEL) }
                val filtered = if (genres.isNotEmpty()) {
                    allNovels.filter { novel -> 
                        novel.genres.any { it in genres }
                    }.take(limit)
                } else {
                    allNovels.take(limit)
                }
                Resource.Success(filtered)
            } else {
                Resource.Error("Failed to get novel recommendations")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting novel recommendations", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Novel recommendations: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun getAnimeRankings(rankingType: String, limit: Int): Resource<List<AnimeContent>> {
        return try {
            val response = apiClient.service.getAnimeRankings(
                rankingType = rankingType,
                limit = limit,
                fields = AnimeRecApp.ANIME_LIST_FIELDS
            )
            
            if (response.isSuccessful && response.body() != null) {
                val animeList = response.body()!!.data.map { it.node.toAnimeContent() }
                Resource.Success(animeList)
            } else {
                Resource.Error("Failed to get rankings")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting rankings", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Rankings: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun getSeasonalAnime(year: Int, season: String, limit: Int): Resource<List<AnimeContent>> {
        return try {
            val response = apiClient.service.getSeasonalAnime(
                year = year,
                season = season,
                limit = limit,
                fields = AnimeRecApp.ANIME_LIST_FIELDS
            )
            
            if (response.isSuccessful && response.body() != null) {
                val animeList = response.body()!!.data.map { it.node.toAnimeContent() }
                Resource.Success(animeList)
            } else {
                Resource.Error("Failed to get seasonal anime")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting seasonal anime", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Seasonal anime: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun getUserAnimeList(status: String?): Resource<List<AnimeContent>> {
        return try {
            val response = ErrorLogManager.logTimed(TAG, "API", "getUserAnimeList(status=$status)") {
                apiClient.service.getUserAnimeList(
                    status = status,
                    fields = AnimeRecApp.ANIME_LIST_FIELDS
                )
            }
            
            if (response.isSuccessful && response.body() != null) {
                val animeList = response.body()!!.data.map { it.node.toAnimeContent() }
                Resource.Success(animeList)
            } else {
                Resource.Error("Failed to get anime list")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting anime list", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Anime list: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun getUserMangaList(status: String?): Resource<List<AnimeContent>> {
        return try {
            val response = ErrorLogManager.logTimed(TAG, "API", "getUserMangaList(status=$status)") {
                apiClient.service.getUserMangaList(
                    status = status,
                    fields = AnimeRecApp.MANGA_LIST_FIELDS
                )
            }
            
            if (response.isSuccessful && response.body() != null) {
                val mangaList = response.body()!!.data.map { it.node.toAnimeContent(ContentType.MANGA) }
                Resource.Success(mangaList)
            } else {
                Resource.Error("Failed to get manga list")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting manga list", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Manga list: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun updateAnimeStatus(animeId: Int, status: String): Resource<Boolean> {
        return try {
            val response = apiClient.service.updateAnimeStatus(
                animeId = animeId,
                status = status
            )
            
            if (response.isSuccessful) {
                Resource.Success(true)
            } else {
                Resource.Error("Failed to update anime status")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating anime status", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Update anime status: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun updateMangaStatus(mangaId: Int, status: String): Resource<Boolean> {
        return try {
            val response = apiClient.service.updateMangaStatus(
                mangaId = mangaId,
                status = status
            )
            
            if (response.isSuccessful) {
                Resource.Success(true)
            } else {
                Resource.Error("Failed to update manga status")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating manga status", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Update manga status: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun removeAnimeFromList(animeId: Int): Resource<Boolean> =
        removeFromList("anime", animeId) { apiClient.service.deleteAnimeListStatus(animeId) }

    override suspend fun removeMangaFromList(mangaId: Int): Resource<Boolean> =
        removeFromList("manga", mangaId) { apiClient.service.deleteMangaListStatus(mangaId) }

    private suspend fun removeFromList(
        kind: String,
        id: Int,
        call: suspend () -> retrofit2.Response<Unit>
    ): Resource<Boolean> {
        return try {
            val response = call()
            // 404 means "already not on the list", which is the state we want.
            if (response.isSuccessful || response.code() == 404) {
                Resource.Success(true)
            } else {
                ErrorLogManager.logEvent(TAG, "ERROR", "Remove $kind $id from list: HTTP ${response.code()}")
                Resource.Error("Failed to remove from list: ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing $kind $id from list", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Remove $kind from list: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun unmarkAsNotInterested(contentId: Int, type: ContentType): Resource<Boolean> {
        return try {
            val key = "${type.idNamespace}:$contentId"
            val keys = getNotInterestedKeysFromStorage().toMutableSet()
            if (keys.remove(key)) {
                saveNotInterestedKeys(keys)
            }
            Resource.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error unmarking as not interested", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Unmark not interested: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun markAsNotInterested(contentId: Int, type: ContentType): Resource<Boolean> {
        return try {
            val key = "${type.idNamespace}:$contentId"
            val keys = getNotInterestedKeysFromStorage().toMutableSet()
            if (keys.add(key)) {
                saveNotInterestedKeys(keys)
            }
            Resource.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking as not interested", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Mark not interested: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun getNotInterestedContentKeys(): Resource<NotInterestedKeys> {
        return try {
            Resource.Success(
                NotInterestedKeys(
                    typedKeys = getNotInterestedKeysFromStorage(),
                    legacyIds = getLegacyNotInterestedIdsFromStorage()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting not interested keys", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Not interested keys: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override fun clearCache() {
        cache.clear()
    }
    
    // ============================================
    // Convenience methods for ViewModels
    // ============================================
    
    override suspend fun getAnimeWatchlist(): Resource<List<AnimeContent>> {
        return getUserAnimeList("plan_to_watch")
    }
    
    override suspend fun getMangaWatchlist(): Resource<List<AnimeContent>> {
        return when (val result = getUserMangaList("plan_to_read")) {
            is Resource.Success -> Resource.Success(
                result.data.filter { it.mediaType !in listOf("light_novel", "novel") }
            )
            is Resource.Error -> result
            is Resource.Loading -> result
        }
    }
    
    override suspend fun getNovelWatchlist(): Resource<List<AnimeContent>> {
        return when (val result = getUserMangaList("plan_to_read")) {
            is Resource.Success -> Resource.Success(
                result.data.filter { it.mediaType in listOf("light_novel", "novel") }
            )
            is Resource.Error -> result
            is Resource.Loading -> result
        }
    }
    
    override suspend fun getAnimeList(status: String): Resource<List<AnimeContent>> {
        return getUserAnimeList(status)
    }
    
    override suspend fun getMangaList(status: String): Resource<List<AnimeContent>> {
        return when (val result = getUserMangaList(status)) {
            is Resource.Success -> Resource.Success(
                result.data.filter { it.mediaType !in listOf("light_novel", "novel") }
            )
            is Resource.Error -> result
            is Resource.Loading -> result
        }
    }
    
    override suspend fun getNovelList(status: String): Resource<List<AnimeContent>> {
        return when (val result = getUserMangaList(status)) {
            is Resource.Success -> Resource.Success(
                result.data.filter { it.mediaType in listOf("light_novel", "novel") }
            )
            is Resource.Error -> result
            is Resource.Loading -> result
        }
    }
    
    override suspend fun rateAnime(animeId: Int, score: Int): Resource<Boolean> {
        return try {
            val response = apiClient.service.updateAnimeStatus(
                animeId = animeId,
                score = score
            )
            if (response.isSuccessful) {
                Resource.Success(true)
            } else {
                Resource.Error("Failed to rate anime")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rating anime", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Rate anime: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    override suspend fun rateManga(mangaId: Int, score: Int): Resource<Boolean> {
        return try {
            val response = apiClient.service.updateMangaStatus(
                mangaId = mangaId,
                score = score
            )
            if (response.isSuccessful) {
                Resource.Success(true)
            } else {
                Resource.Error("Failed to rate manga")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rating manga", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Rate manga: ${e.message}")
            Resource.Error(e.message ?: "Unknown error")
        }
    }
    
    // Helper methods
    private fun loadContentPreferences(): List<String> {
        val json = prefs.getString("content_preferences", null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                listOf("anime")
            }
        } else {
            listOf("anime")
        }
    }
    
    private fun loadGenrePreferences(): List<String> {
        val json = prefs.getString("genre_preferences", null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
    
    /**
     * Namespaced not-interested keys, e.g. "a:1535" (anime) or "m:1535" (manga).
     */
    private fun getNotInterestedKeysFromStorage(): Set<String> {
        val json = prefs.getString(KEY_NOT_INTERESTED_KEYS, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(json, type) ?: emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt not-interested keys, ignoring", e)
            emptySet()
        }
    }
    
    private fun saveNotInterestedKeys(keys: Set<String>) {
        prefs.edit().putString(KEY_NOT_INTERESTED_KEYS, gson.toJson(keys)).apply()
    }
    
    /**
     * Not-interested IDs written before entries carried a content type.
     *
     * These are kept as-is rather than migrated: an untyped ID can't be
     * assigned to a namespace after the fact without guessing, and guessing
     * wrong would either resurrect content the user rejected or hide content
     * they never saw. Callers match them against both namespaces, which is
     * precisely the behaviour these entries already had. Nothing new is ever
     * written here, so the set only shrinks in relevance over time.
     */
    private fun getLegacyNotInterestedIdsFromStorage(): Set<Int> {
        val json = prefs.getString(KEY_NOT_INTERESTED_LEGACY_IDS, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<List<Int>>() {}.type
            gson.fromJson<List<Int>>(json, type)?.toSet() ?: emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt legacy not-interested IDs, ignoring", e)
            emptySet()
        }
    }
    
    private companion object {
        const val KEY_NOT_INTERESTED_KEYS = "not_interested_keys"
        const val KEY_NOT_INTERESTED_LEGACY_IDS = "not_interested_ids"
    }
}
