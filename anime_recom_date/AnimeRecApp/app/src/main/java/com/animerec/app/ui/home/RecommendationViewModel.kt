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
package com.animerec.app.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.animerec.app.AnimeRecApp
import com.animerec.app.data.Resource
import com.animerec.app.models.AnimeContent
import com.animerec.app.models.ContentType
import com.animerec.app.recommendation.RecommendationEngine
import com.animerec.app.recommendation.RecommendationMetrics
import com.animerec.app.recommendation.RecommendationSource
import com.animerec.app.util.ErrorLogManager
import com.animerec.app.util.SingleLiveEvent
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * ViewModel for handling recommendations in the home screen.
 */
class RecommendationViewModel(application: Application) : AndroidViewModel(application) {
    
    private val TAG = "RecommendationViewModel"
    
    private val app = application as AnimeRecApp
    private val repository = app.repository
    private val recommendationEngine = app.recommendationEngine
    
    // Metrics tracker
    private val metrics = RecommendationMetrics()
    
    // Current recommendation cards
    private val _recommendations = MutableLiveData<Resource<List<AnimeContent>>>()
    val recommendations: LiveData<Resource<List<AnimeContent>>> = _recommendations
    
    // Pagination state. @Volatile because these are written from
    // Dispatchers.Default coroutines and read from the main thread.
    @Volatile private var isLoading = false

    /**
     * Set when the engine has no *more* content to append. It only ever gates
     * [loadMoreRecommendations] — never the initial load or a refresh.
     *
     * It used to gate those too, which permanently bricked the feed: one round
     * of all-duplicate results latched it to false, after which
     * [loadRecommendations] returned immediately and "Refresh" did nothing for
     * the rest of the process's life.
     */
    @Volatile private var exhausted = false
    
    // For background recommendation fetching. Synchronized because the
    // prefetch coroutine appends while the main thread drains it.
    private var prefetchJob: Job? = null
    private val prefetchedRecommendations = Collections.synchronizedList(mutableListOf<AnimeContent>())
    
    // Current media type filter (null = all)
    private var currentMediaFilter: String? = null
    // Unfiltered backing list for client-side filtering
    @Volatile private var allRecommendations: List<AnimeContent> = emptyList()

    /**
     * Emitted when the emitted list is a *replacement* rather than an append,
     * so the card stack knows to reset to the top card. Without this the
     * layout manager keeps its old `topPosition` and the next swipe acts on
     * whatever item now happens to sit at that index.
     */
    private val _resetStackPosition = SingleLiveEvent<Unit>()
    val resetStackPosition: LiveData<Unit> = _resetStackPosition

    // ── Undo ────────────────────────────────────────────────────────────
    /** The last swipe, retained so it can be reversed. Null once undone. */
    @Volatile private var lastSwipe: Swipe? = null

    private val _undoAvailable = MutableLiveData(false)
    val undoAvailable: LiveData<Boolean> = _undoAvailable

    /** Fires once per successful undo, carrying the message to show. */
    private val _undoEvent = SingleLiveEvent<String>()
    val undoEvent: LiveData<String> = _undoEvent

    /** One-off user-facing messages that are not tied to the list state. */
    private val _message = SingleLiveEvent<String>()
    val message: LiveData<String> = _message

    private data class Swipe(
        val content: AnimeContent,
        val interaction: RecommendationEngine.InteractionType
    )
    
    init {
        // Start background prefetching of recommendations
        startPrefetching()
    }
    
    /**
     * Load initial recommendations.
     */
    fun loadRecommendations() {
        if (isLoading) return
        
        // If we have prefetched recommendations, use them
        val prefetched = drainPrefetched()
        if (prefetched.isNotEmpty()) {
            allRecommendations = prefetched
            exhausted = false
            emitReplacement(applyMediaFilter(prefetched))
            
            // Start prefetching more in the background
            startPrefetching()
            return
        }
        
        // Otherwise, load from the engine
        isLoading = true
        _recommendations.postValue(Resource.Loading)
        
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Get user profile
                val userResource = repository.getUserProfile()
                if (userResource !is Resource.Success) {
                    _recommendations.postValue(Resource.Error("Failed to load user profile"))
                    isLoading = false
                    return@launch
                }
                
                val user = userResource.data
                
                // Get recommendations
                val recommendationsResource = recommendationEngine.getRecommendations(user, 20)
                
                if (recommendationsResource is Resource.Success) {
                    allRecommendations = recommendationsResource.data
                    exhausted = recommendationsResource.data.isEmpty()
                    emitReplacement(applyMediaFilter(recommendationsResource.data))
                } else {
                    _recommendations.postValue(recommendationsResource)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading recommendations", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Error loading recommendations: ${e.message}")
                _recommendations.postValue(Resource.Error("Error loading recommendations: ${e.message}"))
            } finally {
                isLoading = false
            }
        }
    }
    
    /**
     * Set a media format filter. Null means "All".
     * When a specific content type is selected, fetches fresh content
     * for that type from the recommendation engine if the current pool
     * doesn't contain any items of that type.
     */
    fun setMediaFilter(filter: String?) {
        currentMediaFilter = filter
        ErrorLogManager.logEvent(TAG, "FILTER", "Media filter set to: ${filter ?: "all"}")
        
        if (allRecommendations.isEmpty()) {
            // No data yet — load fresh recommendations
            exhausted = false
            loadRecommendations()
            return
        }
        
        // Apply client-side filter on the cached full list
        val filtered = applyMediaFilter(allRecommendations)
        
        if (filtered.isEmpty() && filter != null) {
            // The current pool has no items of this type — fetch them from the engine
            loadRecommendationsForType(filter)
        } else if (filtered.isEmpty()) {
            _recommendations.postValue(Resource.Error("No content found. Try a different filter."))
        } else {
            // A filter switch swaps in a different list, so the stack must
            // restart from the top card.
            emitReplacement(filtered)
        }
    }
    
    /**
     * Load recommendations specifically for a given content type (manga/novel/anime)
     * by calling the engine's getRecommendationsForType method.
     */
    private fun loadRecommendationsForType(contentType: String) {
        if (isLoading) return
        
        isLoading = true
        _recommendations.postValue(Resource.Loading)
        
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val userResource = repository.getUserProfile()
                if (userResource !is Resource.Success) {
                    _recommendations.postValue(Resource.Error("Failed to load user profile"))
                    isLoading = false
                    return@launch
                }
                
                val user = userResource.data
                val result = recommendationEngine.getRecommendationsForType(user, contentType, 20)
                
                if (result is Resource.Success && result.data.isNotEmpty()) {
                    // Merge into the backing list so switching back to "All" includes them
                    val existingKeys = allRecommendations.map { it.contentKey }.toSet()
                    val unique = result.data.filter { it.contentKey !in existingKeys }
                    allRecommendations = allRecommendations + unique
                    exhausted = false
                    
                    emitReplacement(applyMediaFilter(allRecommendations))
                } else if (result is Resource.Success) {
                    _recommendations.postValue(Resource.Error("No $contentType content available right now."))
                } else if (result is Resource.Error) {
                    _recommendations.postValue(Resource.Error(result.message))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading $contentType recommendations", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Error loading $contentType recs: ${e.message}")
                _recommendations.postValue(Resource.Error("Error loading $contentType: ${e.message}"))
            } finally {
                isLoading = false
            }
        }
    }
    
    /**
     * Filter a list of content by the current media type filter.
     */
    private fun applyMediaFilter(list: List<AnimeContent>): List<AnimeContent> {
        val filter = currentMediaFilter ?: return list
        val targetType = when (filter) {
            "anime" -> ContentType.ANIME
            "manga" -> ContentType.MANGA
            "novel" -> ContentType.NOVEL
            else -> return list
        }
        return list.filter { it.type == targetType }
    }
    
    /**
     * Load more recommendations.
     */
    fun loadMoreRecommendations() {
        if (isLoading || exhausted) return
        
        // If we have prefetched recommendations, use them
        if (_recommendations.value is Resource.Success) {
            val additional = drainPrefetched()
            if (additional.isNotEmpty()) {
                val existingKeys = allRecommendations.map { it.contentKey }.toSet()
                allRecommendations = allRecommendations +
                    additional.filter { it.contentKey !in existingKeys }
                _recommendations.postValue(Resource.Success(applyMediaFilter(allRecommendations)))
                
                // Start prefetching more in the background
                startPrefetching()
                return
            }
        }
        
        // Otherwise, load from the engine
        isLoading = true
        
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Get user profile
                val userResource = repository.getUserProfile()
                if (userResource !is Resource.Success) {
                    _recommendations.postValue(Resource.Error("Failed to load user profile"))
                    isLoading = false
                    return@launch
                }
                
                val user = userResource.data
                
                // Get more recommendations
                val newRecommendationsResource = recommendationEngine.getRecommendations(user, 10)
                
                if (newRecommendationsResource is Resource.Success) {
                    val newRecommendations = newRecommendationsResource.data
                    
                    // De-duplicate against the full (unfiltered) list
                    val existingKeys = allRecommendations.map { it.contentKey }.toSet()
                    val uniqueNewRecommendations = newRecommendations.filter { it.contentKey !in existingKeys }
                    
                    // Store in unfiltered backing list
                    allRecommendations = allRecommendations + uniqueNewRecommendations
                    
                    // Apply current filter and emit — this is an append, so the
                    // stack keeps its position.
                    _recommendations.postValue(Resource.Success(applyMediaFilter(allRecommendations)))
                    exhausted = uniqueNewRecommendations.isEmpty()
                } else if (newRecommendationsResource is Resource.Error) {
                    Log.e(TAG, "Error loading more recommendations: ${newRecommendationsResource.message}")
                    ErrorLogManager.logEvent(TAG, "ERROR", "Error loading more: ${newRecommendationsResource.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading more recommendations", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Exception loading more recs: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }
    
    /**
     * Record a user interaction with a content item.
     */
    fun recordInteraction(content: AnimeContent, interactionType: RecommendationEngine.InteractionType) {
        viewModelScope.launch {
            try {
                // Record in recommendation engine (local preference model only —
                // the MAL write belongs to the caller)
                recommendationEngine.recordInteraction(content, interactionType)
                
                // Record in metrics
                metrics.recordInteraction(
                    interactionType,
                    determineRecommendationSource(content),
                    content.genres
                )
                
                // Log metrics occasionally
                if (metrics.getEngagementRate() > 0 && 
                    (Math.random() < 0.2)) { // 20% chance
                    metrics.logMetricsReport()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recording interaction", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Interaction recording failed: ${e.message}")
            }
        }
    }
    
    /**
     * Add to watchlist when swiped right.
     * Records a LIKE interaction AND updates the MAL list status.
     */
    fun addToWatchlist(content: AnimeContent) {
        rememberSwipe(content, RecommendationEngine.InteractionType.LIKE)
        recordInteraction(content, RecommendationEngine.InteractionType.LIKE)
        viewModelScope.launch {
            try {
                when (content.type) {
                    ContentType.ANIME -> repository.updateAnimeStatus(content.id, "plan_to_watch")
                    ContentType.MANGA, ContentType.NOVEL -> repository.updateMangaStatus(content.id, "plan_to_read")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add to watchlist on MAL", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Watchlist add failed: ${e.message}")
            }
        }
    }
    
    /**
     * Mark as not interested when swiped left.
     * Records a DISLIKE interaction AND stores the "not interested" flag.
     */
    fun markAsNotInterested(content: AnimeContent) {
        rememberSwipe(content, RecommendationEngine.InteractionType.DISLIKE)
        recordInteraction(content, RecommendationEngine.InteractionType.DISLIKE)
        viewModelScope.launch {
            try {
                repository.markAsNotInterested(content.id, content.type)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark as not interested", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Not-interested failed: ${e.message}")
            }
        }
    }
    
    /**
     * Mark as watched/completed when swiped up.
     * Records a SUPER_LIKE interaction AND sets the MAL status to completed.
     */
    fun markAsWatched(content: AnimeContent) {
        rememberSwipe(content, RecommendationEngine.InteractionType.SUPER_LIKE)
        recordInteraction(content, RecommendationEngine.InteractionType.SUPER_LIKE)
        viewModelScope.launch {
            try {
                when (content.type) {
                    ContentType.ANIME -> repository.updateAnimeStatus(content.id, "completed")
                    ContentType.MANGA, ContentType.NOVEL -> repository.updateMangaStatus(content.id, "completed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark as completed on MAL", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Mark completed failed: ${e.message}")
            }
        }
    }
    
    /**
     * Show details when swiped down.
     */
    fun showDetails(content: AnimeContent) {
        // Viewing details writes nothing to MAL, so there is nothing to undo.
        lastSwipe = null
        _undoAvailable.postValue(false)
        recordInteraction(content, RecommendationEngine.InteractionType.VIEW_DETAILS)
    }

    private fun rememberSwipe(content: AnimeContent, interaction: RecommendationEngine.InteractionType) {
        lastSwipe = Swipe(content, interaction)
        _undoAvailable.postValue(true)
    }

    /**
     * Reverse the most recent swipe.
     *
     * Swipes write straight through to the user's real MyAnimeList account, so
     * a mis-swipe used to be permanent and only fixable on the MAL website.
     * Undo reverses the remote write first and only reports success if that
     * write actually landed — otherwise the card would rewind while the user's
     * list still held the bogus entry.
     */
    fun undoLastSwipe() {
        val swipe = lastSwipe ?: return
        lastSwipe = null
        _undoAvailable.postValue(false)

        viewModelScope.launch {
            val content = swipe.content
            val result: Resource<Boolean> = try {
                when (swipe.interaction) {
                    RecommendationEngine.InteractionType.LIKE,
                    RecommendationEngine.InteractionType.SUPER_LIKE ->
                        when (content.type) {
                            ContentType.ANIME -> repository.removeAnimeFromList(content.id)
                            ContentType.MANGA, ContentType.NOVEL -> repository.removeMangaFromList(content.id)
                        }

                    RecommendationEngine.InteractionType.DISLIKE ->
                        repository.unmarkAsNotInterested(content.id, content.type)

                    RecommendationEngine.InteractionType.VIEW_DETAILS ->
                        Resource.Success(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Undo failed", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Undo failed: ${e.message}")
                Resource.Error(e.message ?: "Unknown error")
            }

            if (result is Resource.Success) {
                ErrorLogManager.logEvent(TAG, "UNDO", "Undid ${swipe.interaction} on ${content.contentKey}")
                _undoEvent.postValue("Undid \"${content.title}\"")
            } else {
                // The remote write is still in place, so keep the undo offer up
                // rather than silently pretending it worked.
                lastSwipe = swipe
                _undoAvailable.postValue(true)
                _message.postValue("Couldn't undo — check your connection and try again.")
            }
        }
    }
    
    /**
     * Clear the recommendation cache.
     */
    fun refreshRecommendations() {
        viewModelScope.launch {
            try {
                recommendationEngine.clearCache()
                // Drop the stale pool and the exhausted flag, otherwise a
                // refresh re-emits exactly what the user just ran out of.
                allRecommendations = emptyList()
                prefetchedRecommendations.clear()
                exhausted = false
                loadRecommendations()
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing recommendations", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Refresh failed: ${e.message}")
            }
        }
    }
    
    /**
     * Start prefetching recommendations in the background.
     */
    private fun startPrefetching() {
        // Cancel any existing job
        prefetchJob?.cancel()
        
        // Start a new job
        prefetchJob = viewModelScope.launch {
            try {
                // Wait a bit before prefetching to avoid unnecessary API calls
                delay(2000)
                
                if (!isActive) return@launch
                
                // Get user profile
                val userResource = repository.getUserProfile()
                if (userResource !is Resource.Success) {
                    return@launch
                }
                
                val user = userResource.data
                
                // Get recommendations
                val recommendationsResource = recommendationEngine.getRecommendations(user, 10)
                
                if (recommendationsResource is Resource.Success) {
                    // Add to prefetched list, avoiding duplicates. Dedupe
                    // against the full backing pool rather than only the
                    // currently-visible (possibly filtered) list, so a
                    // filtered view doesn't let already-known items back in.
                    val knownKeys = allRecommendations.map { it.contentKey }.toSet() +
                        synchronized(prefetchedRecommendations) {
                            prefetchedRecommendations.map { it.contentKey }
                        }
                    val uniqueRecommendations = recommendationsResource.data
                        .filter { it.contentKey !in knownKeys }
                    
                    prefetchedRecommendations.addAll(uniqueRecommendations)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error prefetching recommendations", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Prefetch failed: ${e.message}")
            }
        }
    }
    
    /**
     * Determine which recommendation source most likely recommended a content item.
     * In a real implementation, this would track the actual source.
     */
    private fun determineRecommendationSource(content: AnimeContent): RecommendationSource {
        // Just using ID as a simple deterministic way to assign a source
        val sources = RecommendationSource.values()
        val randomIndex = (content.id % sources.size + sources.size) % sources.size
        return sources[randomIndex]
    }
    
    /**
     * Atomically take everything buffered by the prefetcher.
     *
     * The old code did `isNotEmpty()` → `toList()` → `clear()` as three
     * separate steps on an unsynchronized ArrayList that a background
     * coroutine was appending to, which could drop a prefetch batch or throw
     * ConcurrentModificationException mid-iteration.
     */
    private fun drainPrefetched(): List<AnimeContent> =
        synchronized(prefetchedRecommendations) {
            if (prefetchedRecommendations.isEmpty()) {
                emptyList()
            } else {
                val taken = prefetchedRecommendations.toList()
                prefetchedRecommendations.clear()
                taken
            }
        }

    /**
     * Emit a list that *replaces* the current stack contents, signalling the
     * fragment to reset the card stack to position 0.
     */
    private fun emitReplacement(list: List<AnimeContent>) {
        _recommendations.postValue(Resource.Success(list))
        _resetStackPosition.postValue(Unit)
    }

    override fun onCleared() {
        super.onCleared()
        prefetchJob?.cancel()
    }
}