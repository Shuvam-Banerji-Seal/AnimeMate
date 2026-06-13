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

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.animerec.app.models.AnimeContent
import com.animerec.app.models.User
import com.animerec.app.util.ErrorLogManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Model to track and predict user preferences based on their interactions.
 */
class UserPreferenceModel(context: Context) {
    
    private val TAG = "UserPreferenceModel"
    private val prefs: SharedPreferences = context.getSharedPreferences("user_preferences", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Genre weights - higher = more preferred
    private var genreWeights: MutableMap<String, Double> = mutableMapOf()
    
    // Content type weights
    private var contentTypeWeights: MutableMap<String, Double> = mutableMapOf()
    
    // Studio/Author preferences
    private var studioWeights: MutableMap<String, Double> = mutableMapOf()
    
    init {
        loadPreferences()
    }
    
    /**
     * Load preferences from storage.
     */
    private fun loadPreferences() {
        try {
            val genreJson = prefs.getString("genre_weights", "{}")
            val typeToken = object : TypeToken<MutableMap<String, Double>>() {}.type
            genreWeights = gson.fromJson(genreJson, typeToken) ?: mutableMapOf()
            
            val contentJson = prefs.getString("content_type_weights", "{}")
            contentTypeWeights = gson.fromJson(contentJson, typeToken) ?: mutableMapOf()
            
            val studioJson = prefs.getString("studio_weights", "{}")
            studioWeights = gson.fromJson(studioJson, typeToken) ?: mutableMapOf()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading preferences", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Preference load failed: ${e.message}")
            genreWeights = mutableMapOf()
            contentTypeWeights = mutableMapOf()
            studioWeights = mutableMapOf()
        }
    }
    
    /**
     * Save preferences to storage.
     */
    private fun savePreferences() {
        try {
            prefs.edit()
                .putString("genre_weights", gson.toJson(genreWeights))
                .putString("content_type_weights", gson.toJson(contentTypeWeights))
                .putString("studio_weights", gson.toJson(studioWeights))
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving preferences", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Preference save failed: ${e.message}")
        }
    }
    
    /**
     * Update preferences based on a user interaction with content.
     * @param content The content interacted with
     * @param isPositive Whether the interaction was positive (like) or negative (dislike)
     * @param weight The weight of the interaction (default 1.0, super like = 2.0, view = 0.5)
     */
    fun updatePreferencesFromInteraction(content: AnimeContent, isPositive: Boolean, weight: Double = 1.0) {
        val multiplier = if (isPositive) weight else -weight * 0.5
        
        // Update genre weights
        for (genre in content.genres) {
            val currentWeight = genreWeights.getOrDefault(genre, 0.0)
            genreWeights[genre] = (currentWeight + multiplier).coerceIn(-10.0, 10.0)
        }
        
        // Update content type weight
        val contentType = when (content.type) {
            com.animerec.app.models.ContentType.ANIME -> "ANIME"
            com.animerec.app.models.ContentType.MANGA -> "MANGA"
            com.animerec.app.models.ContentType.NOVEL -> "NOVEL"
        }
        val currentTypeWeight = contentTypeWeights.getOrDefault(contentType, 0.0)
        contentTypeWeights[contentType] = (currentTypeWeight + multiplier).coerceIn(-10.0, 10.0)
        
        savePreferences()
    }
    
    /**
     * Rank content for a user based on their preferences.
     * @param content List of content to rank
     * @param user The user to rank for
     * @return Sorted list with most preferred first
     */
    fun rankContentForUser(content: List<AnimeContent>, user: User): List<AnimeContent> {
        return content.sortedByDescending { item -> calculateScore(item, user) }
    }
    
    /**
     * Calculate a preference score for a piece of content.
     */
    private fun calculateScore(content: AnimeContent, user: User): Double {
        var score = 0.0
        
        // Genre score
        for (genre in content.genres) {
            score += genreWeights.getOrDefault(genre, 0.0)
            // Bonus if in user's explicit preferences
            if (genre in user.genrePreferences) {
                score += 2.0
            }
        }
        
        // Content type score
        val contentType = when (content.type) {
            com.animerec.app.models.ContentType.ANIME -> "anime"
            com.animerec.app.models.ContentType.MANGA -> "manga"
            com.animerec.app.models.ContentType.NOVEL -> "novels"
        }
        
        // Also we need to get the weight based on the upper case name saved previously if possible,
        // Wait, earlier I saved "ANIME", "MANGA", "NOVEL"
        val weightKey = when (content.type) {
            com.animerec.app.models.ContentType.ANIME -> "ANIME"
            com.animerec.app.models.ContentType.MANGA -> "MANGA"
            com.animerec.app.models.ContentType.NOVEL -> "NOVEL"
        }
        score += contentTypeWeights.getOrDefault(weightKey, 0.0)
        if (contentType in user.contentPreferences) {
            score += 3.0
        }
        
        // MAL score bonus (normalized to 0-2 range)
        score += (content.malScore / 5.0)
        
        // Popularity bonus (slight preference for popular content)
        if (content.rating > 0) {
            score += (content.rating / 10.0) * 0.5
        }
        
        return score
    }
    
    /**
     * Get the current weight for a specific genre (used by tests and debug UI).
     */
    fun getWeight(genre: String): Double = genreWeights[genre] ?: 0.0

    /**
     * Get the user's top preferred genres.
     */
    fun getTopGenres(limit: Int = 5): List<String> {
        return genreWeights.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    /**
     * Get the user's disliked genres.
     */
    fun getDislikedGenres(): List<String> {
        return genreWeights.entries
            .filter { it.value < -2 }
            .sortedBy { it.value }
            .map { it.key }
    }

    /**
     * Calculate a preference score for a piece of content. Exposed for tests.
     */
    fun scoreForTest(content: AnimeContent, user: User): Double = calculateScore(content, user)
    
    /**
     * Clear all learned preferences.
     */
    fun clearPreferences() {
        genreWeights.clear()
        contentTypeWeights.clear()
        studioWeights.clear()
        savePreferences()
    }
}
