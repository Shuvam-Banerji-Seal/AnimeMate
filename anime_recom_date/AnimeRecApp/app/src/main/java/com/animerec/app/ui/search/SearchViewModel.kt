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
package com.animerec.app.ui.search

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
import com.animerec.app.util.SingleLiveEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ViewModel for the search screen.
 *
 * The MyAnimeList search endpoints were wired all the way through the service
 * and repository but had no UI in front of them — the only way to reach a
 * title was to wait for it to appear in the swipe feed. This exposes them.
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "SearchViewModel"

    private val app = application as AnimeRecApp
    private val repository = app.repository
    private val recommendationEngine = app.recommendationEngine

    /** What the user is searching across. */
    enum class Scope { ALL, ANIME, MANGA, NOVELS }

    private val _results = MutableLiveData<Resource<List<AnimeContent>>>()
    val results: LiveData<Resource<List<AnimeContent>>> = _results

    private val _message = SingleLiveEvent<String>()
    val message: LiveData<String> = _message

    /** True before the user has typed anything, so the screen can show a prompt. */
    private val _idle = MutableLiveData(true)
    val idle: LiveData<Boolean> = _idle

    private var scope: Scope = Scope.ALL
    private var query: String = ""
    private var searchJob: Job? = null

    /**
     * Search as the user types.
     *
     * Debounced: MAL rate-limits aggressively and a request per keystroke both
     * burns the quota and lets a slow early response overwrite a later one.
     * The previous job is cancelled, so only the newest query can emit.
     */
    fun onQueryChanged(newQuery: String) {
        val trimmed = newQuery.trim()
        if (trimmed == query) return
        query = trimmed
        restartSearch(debounce = true)
    }

    fun setScope(newScope: Scope) {
        if (newScope == scope) return
        scope = newScope
        ErrorLogManager.logEvent(TAG, "SEARCH", "Scope set to $newScope")
        restartSearch(debounce = false)
    }

    /** Re-run the current query immediately (pull-to-refresh / retry). */
    fun retry() = restartSearch(debounce = false)

    private fun restartSearch(debounce: Boolean) {
        searchJob?.cancel()

        if (query.length < MIN_QUERY_LENGTH) {
            // MAL rejects queries shorter than 3 characters outright, so don't
            // spend a request to be told so.
            _idle.value = true
            _results.value = Resource.Success(emptyList())
            return
        }

        _idle.value = false
        _results.value = Resource.Loading

        searchJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            _results.postValue(runSearch(query, scope))
        }
    }

    private suspend fun runSearch(q: String, searchScope: Scope): Resource<List<AnimeContent>> {
        return try {
            val results = when (searchScope) {
                Scope.ANIME -> repository.searchAnime(q).orEmpty()
                Scope.MANGA -> repository.searchManga(q).orEmpty().filterNot { it.isLightNovel() }
                Scope.NOVELS -> repository.searchManga(q).orEmpty().filter { it.isLightNovel() }
                Scope.ALL -> coroutineScope {
                    // Both endpoints in parallel — they're independent.
                    // coroutineScope (not viewModelScope) so cancelling the
                    // search job actually cancels these in-flight calls.
                    val anime = async(Dispatchers.IO) { repository.searchAnime(q).orEmpty() }
                    val manga = async(Dispatchers.IO) { repository.searchManga(q).orEmpty() }
                    interleave(anime.await(), manga.await())
                }
            }

            if (results.isEmpty()) {
                Resource.Success(emptyList())
            } else {
                // Namespaced dedupe: an anime and a manga can share an ID.
                Resource.Success(results.distinctBy { it.contentKey })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Superseded by a newer query — let it propagate so no stale
            // result is posted over the newer one.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for '$q'", e)
            ErrorLogManager.logEvent(TAG, "ERROR", "Search failed: ${e.message}")
            Resource.Error("Search failed: ${e.message}")
        }
    }

    /**
     * Alternate anime and manga hits so neither list buries the other.
     * MAL returns each set already ranked by relevance, so taking them in
     * lockstep keeps the strongest matches of both kinds near the top.
     */
    private fun interleave(a: List<AnimeContent>, b: List<AnimeContent>): List<AnimeContent> {
        val merged = mutableListOf<AnimeContent>()
        var i = 0
        while (i < a.size || i < b.size) {
            if (i < a.size) merged.add(a[i])
            if (i < b.size) merged.add(b[i])
            i++
        }
        return merged
    }

    private fun Resource<List<AnimeContent>>.orEmpty(): List<AnimeContent> =
        (this as? Resource.Success)?.data ?: emptyList()

    private fun AnimeContent.isLightNovel(): Boolean =
        mediaType in LIGHT_NOVEL_MEDIA_TYPES

    /**
     * Add a search result to the user's MAL list (plan to watch / plan to read).
     */
    fun addToList(content: AnimeContent) {
        viewModelScope.launch {
            val result = when (content.type) {
                ContentType.ANIME -> repository.updateAnimeStatus(content.id, "plan_to_watch")
                ContentType.MANGA, ContentType.NOVEL -> repository.updateMangaStatus(content.id, "plan_to_read")
            }

            if (result is Resource.Success) {
                recommendationEngine.recordInteraction(content, RecommendationEngine.InteractionType.LIKE)
                val list = if (content.type == ContentType.ANIME) "watchlist" else "readlist"
                _message.postValue("Added \"${content.title}\" to your $list")
            } else {
                _message.postValue("Couldn't add \"${content.title}\" — try again")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
    }

    private companion object {
        /** MAL's `q` parameter requires at least 3 characters. */
        const val MIN_QUERY_LENGTH = 3
        const val DEBOUNCE_MS = 350L
        val LIGHT_NOVEL_MEDIA_TYPES = setOf("light_novel", "novel")
    }
}
