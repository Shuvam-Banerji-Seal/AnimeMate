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
package com.animerec.app.recommendation

import android.util.Log
import com.animerec.app.data.AnimeRepository
import com.animerec.app.data.Resource
import com.animerec.app.models.AnimeContent
import com.animerec.app.models.ContentType
import com.animerec.app.models.User
import com.animerec.app.util.ErrorLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/**
 * Twitter/X-style recommendation engine.
 *
 * Adapted from Twitter's open-source "the-algorithm" ranking approach:
 *  1. Engagement prediction  → user interaction history weights
 *  2. Content-user affinity  → genre/type matching with learned weights
 *  3. Temporal decay          → boost for currently airing / recently released content
 *  4. Diversity injection     → ensure genre variety, avoid filter bubbles
 *  5. Social proof            → MAL score & popularity as proxy
 *  6. Negative signals        → penalise disliked genres
 *  7. Exploration (20%) vs Exploitation (80%)
 */
class BasicRecommendationEngine(
    private val repository: AnimeRepository,
    private val userPreferenceModel: UserPreferenceModel
) : RecommendationEngine {
    
    private val TAG = "BasicRecommendationEngine"
    
    // Thread-safe LRU cache for recommendations. accessOrder = true → the
    // eldest (least-recently-accessed) entry is removed when we exceed
    // [RECOMMENDATION_CACHE_SIZE]. The previous implementation was a plain
    // LinkedHashMap corrupted by concurrent async coroutines.
    private val recommendationCache = object : LinkedHashMap<String, Pair<List<AnimeContent>, Long>>(
        /* initialCapacity = */ 32,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<List<AnimeContent>, Long>>?): Boolean {
            return size > RECOMMENDATION_CACHE_SIZE
        }
    }
    private val cacheLock = Any()
    
    // Cache expiration time in milliseconds (10 minutes — shorter for more variety)
    private val CACHE_EXPIRATION = 10 * 60 * 1000L
    
    // ──────────────────────────────────────────────
    //  Twitter-style ranking weights
    // ──────────────────────────────────────────────
    companion object {
        // Engagement prediction weights
        private const val W_GENRE_AFFINITY = 3.0      // Per matching preferred genre
        private const val W_GENRE_NEGATIVE = -2.5     // Per matching disliked genre
        private const val W_CONTENT_TYPE_MATCH = 2.0  // Content type user prefers
        private const val W_LEARNED_GENRE = 1.0       // From UserPreferenceModel weights
        
        // Social proof weights
        private const val W_MAL_SCORE = 1.5           // MAL community score (0-10 → 0-1.5)
        private const val W_POPULARITY = 0.8          // Popularity bonus (log-scaled)
        
        // Temporal decay
        private const val W_AIRING_BOOST = 4.0        // Currently airing content boost
        private const val W_RECENT_BOOST = 2.0        // Released in past 2 years
        
        // Diversity
        private const val EXPLOITATION_RATIO = 0.80   // 80% personalised, 20% exploration
        private const val MAX_SAME_GENRE_RATIO = 0.4  // Max 40% from same genre
        
        private const val RECOMMENDATION_CACHE_SIZE = 50
        
        // Ranking types for diversified API fetching
        private val ANIME_RANKING_TYPES = listOf("all", "airing", "bypopularity", "favorite", "upcoming")
        private val MANGA_RANKING_TYPES = listOf("all", "bypopularity", "favorite")
    }
    
    override suspend fun getRecommendations(
        user: User,
        limit: Int
    ): Resource<List<AnimeContent>> = withContext(Dispatchers.IO) {
        try {
            val recsStart = System.currentTimeMillis()
            ErrorLogManager.logEvent(TAG, "RECS", "Starting recommendation generation for user=${user.name}, limit=$limit")

            // Use a time-seeded cache key so results change between sessions.
            // The key includes user.id (not name) so two users with the same
            // name don't share a cache entry. It also includes the genre
            // preferences and content preferences so that changing preferences
            // mid-session busts the cache.
            val timeSlot = System.currentTimeMillis() / CACHE_EXPIRATION
            val genresKey = user.genrePreferences.sorted().joinToString(",")
            val typesKey = user.contentPreferences.sorted().joinToString(",")
            val cacheKey = "recs_${user.id}_${limit}_${timeSlot}_${genresKey}_${typesKey}"
            val cachedRecommendations = getFromCache(cacheKey)
            if (cachedRecommendations != null) {
                return@withContext Resource.Success(cachedRecommendations)
            }
            
            val contentTypes = user.contentPreferences
            if (contentTypes.isEmpty()) {
                return@withContext Resource.Error("No content types selected")
            }
            
            // ── Step 1: Gather a large candidate pool from diverse sources ──
            val candidatePool = mutableListOf<AnimeContent>()
            val itemsPerType = (limit * 3) / contentTypes.size  // fetch 3× for filtering headroom
            
            val deferredResults = contentTypes.map { contentType ->
                async {
                    getRecommendationsForType(user, contentType, itemsPerType)
                }
            }
            val results = deferredResults.awaitAll()
            for (typeResult in results) {
                if (typeResult is Resource.Success) {
                    candidatePool.addAll(typeResult.data)
                }
            }
            
            // ── Step 2: Remove duplicates and already-seen / not-interested ──
            val notInterestedIds = (repository.getNotInterestedIds() as? Resource.Success)?.data ?: emptyList()

            // Fetch user's existing anime/manga lists to exclude already-watched content
            val userAnimeIds = (repository.getUserAnimeList(null) as? Resource.Success)
                ?.data?.map { it.id }?.toSet() ?: emptySet()
            val userMangaIds = (repository.getUserMangaList(null) as? Resource.Success)
                ?.data?.map { it.id }?.toSet() ?: emptySet()
            val exclusionSet = notInterestedIds.toSet() + userAnimeIds + userMangaIds

            val uniqueCandidates = candidatePool
                .distinctBy { it.id }
                .filter { it.id !in exclusionSet }
            
            // ── Step 3: Twitter-style ranking ──
            val scored = uniqueCandidates.map { content ->
                content to calculateTwitterScore(content, user)
            }
            
            // ── Step 4: Exploitation / Exploration split ──
            val exploitationCount = (limit * EXPLOITATION_RATIO).toInt()
            val explorationCount = limit - exploitationCount
            
            // Top-ranked (exploitation)
            val rankedPool = scored.sortedByDescending { it.second }.map { it.first }
            val exploitationPicks = rankedPool.take(exploitationCount)
            
            // Exploration: random sample from remaining (excluding exploitation picks)
            val exploitationIds = exploitationPicks.map { it.id }.toSet()
            val explorationPool = rankedPool.filter { it.id !in exploitationIds }
            val explorationPicks = if (explorationPool.size > explorationCount) {
                explorationPool.shuffled(Random(System.nanoTime())).take(explorationCount)
            } else {
                explorationPool
            }
            
            // ── Step 5: Merge and apply diversity injection ──
            val merged = (exploitationPicks + explorationPicks).toMutableList()
            val diversified = applyDiversityInjection(merged, limit)
            
            // Cache the results
            addToCache(cacheKey, diversified)

            val elapsed = System.currentTimeMillis() - recsStart
            ErrorLogManager.logEvent(TAG, "RECS", "Recommendation generation completed in ${elapsed}ms — ${diversified.size} items (candidates=${candidatePool.size}, unique=${uniqueCandidates.size})")

            return@withContext Resource.Success(diversified)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recommendations", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Recommendation generation failed: ${e.message}")
            return@withContext Resource.Error("Error getting recommendations: ${e.message}")
        }
    }
    
    /**
     * Calculate a Twitter-style composite score for ranking content.
     */
    private fun calculateTwitterScore(content: AnimeContent, user: User): Double {
        var score = 0.0
        
        // ── 1. Content-user affinity (genre match) ──
        val preferredGenres = user.genrePreferences.toSet()
        val dislikedGenres = userPreferenceModel.getDislikedGenres().toSet()
        
        for (genre in content.genres) {
            if (genre in preferredGenres) score += W_GENRE_AFFINITY
            if (genre in dislikedGenres) score += W_GENRE_NEGATIVE
        }
        
        // ── 2. Learned preference weights from interaction history ──
        val topGenres = userPreferenceModel.getTopGenres(10)
        for (genre in content.genres) {
            if (genre in topGenres) score += W_LEARNED_GENRE
        }
        
        // ── 3. Content type preference ──
        // contentPreferences stores "anime", "manga", "novels" (with 's').
        // ContentType.NOVEL.name.lowercase() == "novel" so we normalise.
        val contentTypeName = when (content.type) {
            ContentType.ANIME -> "anime"
            ContentType.MANGA -> "manga"
            ContentType.NOVEL -> "novels"
        }
        if (contentTypeName in user.contentPreferences) {
            score += W_CONTENT_TYPE_MATCH
        }
        
        // ── 4. Social proof: MAL score (normalised 0-10 → 0-W) ──
        if (content.malScore > 0) {
            score += (content.malScore / 10.0) * W_MAL_SCORE
        }
        
        // ── 5. Social proof: Popularity (log-scaled to avoid domination) ──
        if (content.rating > 0) {
            score += ln(content.rating + 1.0) * W_POPULARITY * 0.3
        }
        
        // ── 6. Temporal boost ──
        val statusLower = content.airingStatus.lowercase()
        if (statusLower.contains("airing") || statusLower.contains("currently")) {
            score += W_AIRING_BOOST
        } else if (content.releaseYear != null) {
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            if (currentYear - content.releaseYear <= 2) {
                score += W_RECENT_BOOST
            }
        }
        
        // ── 7. Small random jitter to prevent identical rankings ──
        score += Random.nextDouble(0.0, 1.5)
        
        return score
    }
    
    /**
     * Apply diversity injection (adapted from Twitter's diversity mixer).
     * Ensures no single genre dominates the feed.
     *
     * Important: the cap is enforced against EVERY genre in an item, not just
     * the first. An item with genres [Action, Comedy, Romance] counts +1
     * against each of those three buckets. This prevents the all-Items-bucketed-
     * as-Action anti-pattern that defeated the 40% cap in the previous
     * implementation.
     */
    private fun applyDiversityInjection(items: MutableList<AnimeContent>, limit: Int): List<AnimeContent> {
        if (items.size <= 1) return items.take(limit)

        val result = mutableListOf<AnimeContent>()
        // Track count for every genre we've ever seen.
        val genreCounts = mutableMapOf<String, Int>()
        // Limit per item is ceiling(limit * cap), with floor of 1.
        val maxPerGenre = (limit * MAX_SAME_GENRE_RATIO).toInt().coerceAtLeast(1)

        fun itemAllowed(item: AnimeContent): Boolean {
            val genres = item.genres.ifEmpty { listOf("Unknown") }
            // Allowed iff every genre of this item still has capacity.
            return genres.all { (genreCounts[it] ?: 0) < maxPerGenre }
        }

        // First pass: add items respecting multi-genre cap
        for (item in items) {
            if (result.size >= limit) break
            if (itemAllowed(item)) {
                result.add(item)
                for (g in item.genres.ifEmpty { listOf("Unknown") }) {
                    genreCounts[g] = (genreCounts[g] ?: 0) + 1
                }
            }
        }

        // Second pass: fill remaining slots with any items not yet added.
        // If the first pass yielded fewer than `limit` items because every
        // candidate was blocked by the cap, we relax the cap and add anyway.
        if (result.size < limit) {
            val resultIds = result.map { it.id }.toSet()
            for (item in items) {
                if (result.size >= limit) break
                if (item.id !in resultIds) {
                    result.add(item)
                }
            }
        }

        return shuffleInWindows(result, windowSize = 4)
    }

    /** Exposed for tests so we can verify the cap behaviour without spinning up the full pipeline. */
    internal fun applyDiversityInjectionForTest(items: MutableList<AnimeContent>, limit: Int): List<AnimeContent> =
        applyDiversityInjection(items, limit)
    
    /**
     * Shuffle items within fixed-size windows to add variety
     * while preserving approximate rank ordering.
     */
    private fun shuffleInWindows(items: List<AnimeContent>, windowSize: Int): List<AnimeContent> {
        val result = mutableListOf<AnimeContent>()
        val rng = Random(System.nanoTime())
        
        var i = 0
        while (i < items.size) {
            val end = min(i + windowSize, items.size)
            val window = items.subList(i, end).toMutableList()
            window.shuffle(rng)
            result.addAll(window)
            i = end
        }
        return result
    }
    
    override suspend fun getRecommendationsForType(
        user: User,
        contentType: String,
        limit: Int
    ): Resource<List<AnimeContent>> = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "type_${user.id}_${contentType}_${limit}_${user.genrePreferences.sorted().joinToString(",")}"
            val cachedRecommendations = getFromCache(cacheKey)
            if (cachedRecommendations != null) {
                return@withContext Resource.Success(cachedRecommendations)
            }
            
            val genres = user.genrePreferences
            val allItems = mutableListOf<AnimeContent>()
            
            // Normalise "novel" → "novels" so the chip filter value works
            val normalizedType = if (contentType == "novel") "novels" else contentType
            
            // ── Fetch from multiple ranking types for diversity ──
            val rankingTypes = when (normalizedType) {
                "anime" -> ANIME_RANKING_TYPES
                "manga" -> MANGA_RANKING_TYPES
                "novels" -> listOf("novels", "bypopularity")
                else -> listOf("all")
            }
            
                        val perRankingLimit = (limit * 2) / rankingTypes.size
            if (perRankingLimit < 1) return@withContext Resource.Error("Limit too small")
            
            val deferreds = rankingTypes.map { rankingType ->
                async {
                    try {
                        val result = when (normalizedType) {
                            "anime" -> repository.getAnimeRecommendations(genres, perRankingLimit, rankingType)
                            "manga" -> repository.getMangaRecommendations(genres, perRankingLimit, rankingType)
                            "novels" -> repository.getNovelRecommendations(genres, perRankingLimit, rankingType)
                            else -> Resource.Error("Invalid content type: $normalizedType")
                        }
                        if (result is Resource.Success) result.data else emptyList()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch $rankingType for $contentType", e)
                        emptyList()
                    }
                }
            }
            
            val suggestionsDeferred: kotlinx.coroutines.Deferred<List<AnimeContent>>? = if (normalizedType == "anime") {
                async {
                    try {
                        val suggestionsResult = repository.getRecommendations(limit)
                        if (suggestionsResult is Resource.Success) suggestionsResult.data else emptyList()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch MAL suggestions", e)
                        emptyList()
                    }
                }
            } else null
            
            val results = deferreds.awaitAll()
            for (res in results) {
                allItems.addAll(res)
            }
            if (suggestionsDeferred != null) {
                allItems.addAll(suggestionsDeferred.await())
            }
            
            // De-duplicate
            val uniqueItems = allItems.distinctBy { it.id }
            
            // Filter by genres if provided
            val filtered = if (genres.isNotEmpty()) {
                val genreSet = genres.toSet()
                uniqueItems.filter { item ->
                    item.genres.any { it in genreSet }
                }
            } else {
                uniqueItems
            }
            
            // Filter out not-interested and already-watched
            val notInterestedIds = (repository.getNotInterestedIds() as? Resource.Success)?.data ?: emptyList()
            val userAnimeIds = (repository.getUserAnimeList(null) as? Resource.Success)
                ?.data?.map { it.id }?.toSet() ?: emptySet()
            val userMangaIds = (repository.getUserMangaList(null) as? Resource.Success)
                ?.data?.map { it.id }?.toSet() ?: emptySet()
            val exclusionSet = notInterestedIds.toSet() + userAnimeIds + userMangaIds
            val cleanList = filtered.filter { it.id !in exclusionSet }
            
            val limitedList = cleanList.take(limit)
            addToCache(cacheKey, limitedList)
            
            return@withContext Resource.Success(limitedList)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recommendations for type $contentType", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Recs for type $contentType failed: ${e.message}")
            return@withContext Resource.Error("Error getting recommendations: ${e.message}")
        }
    }
    
    override suspend fun getSimilarContent(
        contentId: Int,
        limit: Int
    ): Resource<List<AnimeContent>> = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "similar_${contentId}_$limit"
            val cachedRecommendations = getFromCache(cacheKey)
            if (cachedRecommendations != null) {
                return@withContext Resource.Success(cachedRecommendations)
            }
            
            val contentResource = repository.getAnimeDetails(contentId)
            
            if (contentResource is Resource.Success) {
                val content = contentResource.data
                
                val similarResource = when (content.type) {
                    ContentType.ANIME -> repository.getAnimeRecommendations(content.genres, limit * 2)
                    ContentType.MANGA -> repository.getMangaRecommendations(content.genres, limit * 2)
                    ContentType.NOVEL -> repository.getNovelRecommendations(content.genres, limit * 2)
                }
                
                if (similarResource is Resource.Success) {
                    val filteredContent = similarResource.data
                        .filter { it.id != contentId }
                        .sortedByDescending { calculateSimilarity(content, it) }
                        .take(limit)
                    
                    addToCache(cacheKey, filteredContent)
                    return@withContext Resource.Success(filteredContent)
                } else if (similarResource is Resource.Error) {
                    return@withContext Resource.Error(similarResource.message)
                }
            } else if (contentResource is Resource.Error) {
                return@withContext Resource.Error(contentResource.message)
            }
            
            return@withContext Resource.Error("Failed to get similar content")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting similar content for ID $contentId", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Similar content for ID=$contentId failed: ${e.message}")
            return@withContext Resource.Error("Error getting similar content: ${e.message}")
        }
    }
    
    override suspend fun recordInteraction(
        contentId: Int,
        interactionType: RecommendationEngine.InteractionType
    ): Resource<Boolean> = withContext(Dispatchers.IO) {
        try {
            val contentResource = repository.getAnimeDetails(contentId)
            
            if (contentResource !is Resource.Success) {
                return@withContext Resource.Error("Failed to get content details")
            }
            
            val content = contentResource.data
            
            when (interactionType) {
                RecommendationEngine.InteractionType.LIKE -> {
                    val status = when (content.type) {
                        ContentType.ANIME -> "plan_to_watch"
                        ContentType.MANGA, ContentType.NOVEL -> "plan_to_read"
                    }
                    val result = when (content.type) {
                        ContentType.ANIME -> repository.updateAnimeStatus(contentId, status)
                        ContentType.MANGA, ContentType.NOVEL -> repository.updateMangaStatus(contentId, status)
                    }
                    userPreferenceModel.updatePreferencesFromInteraction(content, true)
                    return@withContext result
                }
                RecommendationEngine.InteractionType.DISLIKE -> {
                    val result = repository.markAsNotInterested(contentId)
                    userPreferenceModel.updatePreferencesFromInteraction(content, false)
                    // Clear recommendation cache so disliked genres are deprioritised immediately
                    recommendationCache.clear()
                    return@withContext result
                }
                RecommendationEngine.InteractionType.SUPER_LIKE -> {
                    val result = when (content.type) {
                        ContentType.ANIME -> repository.updateAnimeStatus(contentId, "completed")
                        ContentType.MANGA, ContentType.NOVEL -> repository.updateMangaStatus(contentId, "completed")
                    }
                    userPreferenceModel.updatePreferencesFromInteraction(content, true, weight = 2.0)
                    return@withContext result
                }
                RecommendationEngine.InteractionType.VIEW_DETAILS -> {
                    userPreferenceModel.updatePreferencesFromInteraction(content, true, weight = 0.5)
                    return@withContext Resource.Success(true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording interaction for ID $contentId", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Interaction for ID=$contentId failed: ${e.message}")
            return@withContext Resource.Error("Error recording interaction: ${e.message}")
        }
    }
    
    override fun clearCache(): Resource<Boolean> {
        return try {
            synchronized(cacheLock) { recommendationCache.clear() }
            Resource.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Cache clear failed: ${e.message}")
            Resource.Error("Error clearing cache: ${e.message}")
        }
    }

    /**
     * Calculate similarity score between two content items.
     */
    private fun calculateSimilarity(content1: AnimeContent, content2: AnimeContent): Double {
        var score = 0.0

        val genreOverlap = content1.genres.intersect(content2.genres.toSet()).size
        score += genreOverlap * 10.0

        if (content1.rating > 0 && content2.rating > 0) {
            val ratingDiff = Math.abs(content1.rating - content2.rating)
            score += (10.0 - ratingDiff) * 2.0
        }

        if (content1.type == content2.type) score += 5.0
        if (content1.status == content2.status) score += 3.0

        return score
    }

    /**
     * Get recommendations from cache if available and not expired.
     */
    private fun getFromCache(key: String): List<AnimeContent>? {
        return synchronized(cacheLock) {
            val cachedValue = recommendationCache[key]
            if (cachedValue != null) {
                val (recommendations, timestamp) = cachedValue
                if (System.currentTimeMillis() - timestamp < CACHE_EXPIRATION) {
                    recommendations
                } else {
                    recommendationCache.remove(key)
                    null
                }
            } else {
                null
            }
        }
    }

    /**
     * Add recommendations to cache.
     */
    private fun addToCache(key: String, recommendations: List<AnimeContent>) {
        synchronized(cacheLock) {
            recommendationCache[key] = Pair(recommendations, System.currentTimeMillis())
        }
    }
}