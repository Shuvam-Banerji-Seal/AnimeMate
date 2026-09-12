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
package com.animerec.app.ui.details

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
import com.animerec.app.util.ErrorLogManager
import kotlinx.coroutines.launch

/**
 * ViewModel for the content details screen.
 */
class DetailsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val TAG = "DetailsViewModel"
    private val repository = (application as AnimeRecApp).repository
    private val recommendationEngine = (application as AnimeRecApp).recommendationEngine
    
    private val _contentDetails = MutableLiveData<Resource<AnimeContent>>()
    val contentDetails: LiveData<Resource<AnimeContent>> = _contentDetails
    
    private val _similarContent = MutableLiveData<Resource<List<AnimeContent>>>()
    val similarContent: LiveData<Resource<List<AnimeContent>>> = _similarContent
    
    /**
     * Load content details.
     */
    fun loadContentDetails(contentId: Int, contentType: ContentType) {
        _contentDetails.value = Resource.Loading
        
        viewModelScope.launch {
            try {
                val result = when (contentType) {
                    ContentType.ANIME -> repository.getAnimeDetails(contentId)
                    ContentType.MANGA, ContentType.NOVEL -> repository.getMangaDetails(contentId)
                }
                
                _contentDetails.value = result
                
                // Record the interaction for recommendation engine
                if (result is Resource.Success) {
                    recommendationEngine.recordInteraction(
                        result.data,
                        RecommendationEngine.InteractionType.VIEW_DETAILS
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading content details", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Details load failed: ${e.message}")
                _contentDetails.value = Resource.Error("Failed to load details: ${e.message}")
            }
        }
    }
    
    /**
     * Load similar content recommendations.
     */
    fun loadSimilarContent(contentId: Int, contentType: ContentType) {
        viewModelScope.launch {
            try {
                val result = recommendationEngine.getSimilarContent(contentId, contentType, 10)
                _similarContent.value = result
            } catch (e: Exception) {
                Log.e(TAG, "Error loading similar content", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Similar content load failed: ${e.message}")
                _similarContent.value = Resource.Error("Failed to load similar content")
            }
        }
    }
    
    /**
     * Update the status of the content.
     */
    fun updateStatus(content: AnimeContent, newStatus: String) {
        viewModelScope.launch {
            try {
                val result = when (content.type) {
                    ContentType.ANIME -> repository.updateAnimeStatus(content.id, newStatus)
                    ContentType.MANGA, ContentType.NOVEL -> repository.updateMangaStatus(content.id, newStatus)
                }
                
                if (result is Resource.Success) {
                    // Record the interaction for recommendation engine
                    val interactionType = when (newStatus) {
                        "plan_to_watch", "plan_to_read" -> RecommendationEngine.InteractionType.LIKE
                        "completed" -> RecommendationEngine.InteractionType.SUPER_LIKE
                        "dropped" -> RecommendationEngine.InteractionType.DISLIKE
                        else -> RecommendationEngine.InteractionType.LIKE
                    }
                    
                    recommendationEngine.recordInteraction(content, interactionType)
                    
                    // Dropping something is an explicit "stop showing me this".
                    // The engine no longer writes to the repository itself, so
                    // record it here where the intent is unambiguous.
                    if (newStatus == "dropped") {
                        repository.markAsNotInterested(content.id, content.type)
                    }
                    
                    // Refresh content details
                    loadContentDetails(content.id, content.type)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating status", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Status update failed: ${e.message}")
            }
        }
    }
    
    /**
     * Rate the content.
     */
    fun rateContent(content: AnimeContent, score: Int) {
        viewModelScope.launch {
            try {
                val result = when (content.type) {
                    ContentType.ANIME -> repository.rateAnime(content.id, score)
                    ContentType.MANGA, ContentType.NOVEL -> repository.rateManga(content.id, score)
                }
                
                if (result is Resource.Success) {
                    // Record interaction based on score
                    val interactionType = when {
                        score >= 7 -> RecommendationEngine.InteractionType.SUPER_LIKE
                        score >= 4 -> RecommendationEngine.InteractionType.LIKE
                        else -> RecommendationEngine.InteractionType.DISLIKE
                    }
                    
                    recommendationEngine.recordInteraction(content, interactionType)
                    
                    // Refresh content details
                    loadContentDetails(content.id, content.type)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rating content", e)
                ErrorLogManager.logEvent(TAG, "ERROR", "Rating failed: ${e.message}")
            }
        }
    }
}