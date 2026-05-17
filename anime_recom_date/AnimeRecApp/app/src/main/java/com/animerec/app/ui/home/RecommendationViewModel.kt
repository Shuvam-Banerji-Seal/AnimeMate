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
    
    // Keep track of pagination
    private var isLoading = false
    private var hasMoreData = true
    
    // For background recommendation fetching
    private var prefetchJob: Job? = null
    private val prefetchedRecommendations = mutableListOf<AnimeContent>()
    
    // Current media type filter (null = all)
    private var currentMediaFilter: String? = null
    // Unfiltered backing list for client-side filtering
    private var allRecommendations: List<AnimeContent> = emptyList()
    
    init {
        // Start background prefetching of recommendations
        startPrefetching()
    }
    
    /**
     * Load initial recommendations.
     */
    fun loadRecommendations() {
        if (isLoading || !hasMoreData) return
        
        // If we have prefetched recommendations, use them
        if (prefetchedRecommendations.isNotEmpty()) {
            val recommendations = prefetchedRecommendations.toList()
            prefetchedRecommendations.clear()
            allRecommendations = recommendations
            _recommendations.postValue(Resource.Success(applyMediaFilter(recommendations)))
            
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
                    _recommendations.postValue(Resource.Success(applyMediaFilter(recommendationsResource.data)))
                } else {
                    _recommendations.postValue(recommendationsResource)
                }
                hasMoreData = (recommendationsResource is Resource.Success) && 
                             (recommendationsResource.data.isNotEmpty())
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
            hasMoreData = true
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
            _recommendations.postValue(Resource.Success(filtered))
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
                    val existingIds = allRecommendations.map { it.id }.toSet()
                    val unique = result.data.filter { it.id !in existingIds }
                    allRecommendations = allRecommendations + unique
                    
                    _recommendations.postValue(Resource.Success(result.data))
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
        if (isLoading || !hasMoreData) return
        
        // If we have prefetched recommendations, use them
        if (prefetchedRecommendations.isNotEmpty()) {
            val currentValue = _recommendations.value
            if (currentValue is Resource.Success) {
                val additionalRecommendations = prefetchedRecommendations.toList()
                prefetchedRecommendations.clear()
                
                allRecommendations = allRecommendations + additionalRecommendations
                val filtered = applyMediaFilter(allRecommendations)
                _recommendations.postValue(Resource.Success(filtered))
                
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
                    val existingIds = allRecommendations.map { it.id }.toSet()
                    val uniqueNewRecommendations = newRecommendations.filter { it.id !in existingIds }
                    
                    // Store in unfiltered backing list
                    allRecommendations = allRecommendations + uniqueNewRecommendations
                    
                    // Apply current filter and emit
                    val filteredList = applyMediaFilter(allRecommendations)
                    _recommendations.postValue(Resource.Success(filteredList))
                    hasMoreData = uniqueNewRecommendations.isNotEmpty()
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
                // Record in recommendation engine
                recommendationEngine.recordInteraction(content.id, interactionType)
                
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
     * @param status The MAL status to set (e.g., "plan_to_watch", "watching").
     */
    fun addToWatchlist(content: AnimeContent, status: String) {
        recordInteraction(content, RecommendationEngine.InteractionType.LIKE)
        viewModelScope.launch {
            try {
                when (content.type) {
                    ContentType.ANIME -> repository.updateAnimeStatus(content.id, status)
                    ContentType.MANGA, ContentType.NOVEL -> repository.updateMangaStatus(content.id, status)
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
        recordInteraction(content, RecommendationEngine.InteractionType.DISLIKE)
        viewModelScope.launch {
            try {
                repository.markAsNotInterested(content.id)
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
        recordInteraction(content, RecommendationEngine.InteractionType.VIEW_DETAILS)
    }
    
    /**
     * Clear the recommendation cache.
     */
    fun refreshRecommendations() {
        viewModelScope.launch {
            try {
                recommendationEngine.clearCache()
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
                    val recommendations = recommendationsResource.data
                    
                    // Add to prefetched list, avoiding duplicates
                    val prefetchCurrentValue = _recommendations.value
                    val currentIds = if (prefetchCurrentValue is Resource.Success) prefetchCurrentValue.data.map { it.id } else emptyList()
                    val uniqueRecommendations = recommendations.filter { it.id !in currentIds }
                    
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
    
    override fun onCleared() {
        super.onCleared()
        prefetchJob?.cancel()
    }
}