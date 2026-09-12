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
package com.animerec.app.models

data class AnimeContent(
    val id: Int,
    val title: String,
    val alternativeTitles: Map<String, String> = mapOf(),
    val synopsis: String = "",
    val imageUrl: String = "",
    val type: ContentType = ContentType.ANIME,
    val status: String = "",
    val genres: List<String> = listOf(),
    val rating: Double = 0.0,
    val releaseYear: Int? = null,
    val episodes: Int? = null,
    val chapters: Int? = null,
    val volumes: Int? = null,
    val trailerUrl: String? = null,
    val malScore: Double = 0.0,
    val userScore: Int? = null,
    val airingStatus: String = "",
    val isFavorite: Boolean = false,
    val inWatchlist: Boolean = false,
    val isCompleted: Boolean = false,
    // Additional properties for compatibility
    val mediaType: String = "",
    val startDate: String? = null,
    val endDate: String? = null,
    val numEpisodes: Int? = null,

    // ── Fields MAL already returns ────────────────────────────────────────
    // These were requested from the API and parsed into the response DTOs,
    // then dropped on the floor by the mapping layer. Carrying them costs
    // nothing extra over the wire and makes the detail screen (and studio
    // preference learning) possible.
    /** Animation studios (anime) — empty for manga and novels. */
    val studios: List<String> = listOf(),
    /** Authors (manga/novels) — empty for anime. */
    val authors: List<String> = listOf(),
    /** MAL score rank, 1 = highest rated. Null when unranked. */
    val rank: Int? = null,
    /** MAL popularity rank, 1 = most popular. Null when unranked. */
    val popularity: Int? = null,
    /** How many MAL users have this on a list. */
    val numListUsers: Int = 0,
    /** Original medium, e.g. "manga", "light_novel", "original". */
    val source: String = "",
    /** Mean episode length in seconds (anime only). */
    val averageEpisodeDurationSeconds: Int? = null
) {
    /**
     * Namespaced identity for this item.
     *
     * MyAnimeList keeps **separate** ID spaces for anime and manga, so a bare
     * `id` is ambiguous: anime 1535 (Death Note) and manga 1535 (Boys Next
     * Door) are different works that share a number. Anything that dedupes,
     * excludes or diffs content must key on this instead of `id`, otherwise a
     * manga on the user's read list silently suppresses an unrelated anime
     * from their recommendations (and vice versa).
     *
     * Light novels live in the manga ID space on MAL, so NOVEL shares the
     * MANGA namespace.
     */
    val contentKey: String
        get() = "${type.idNamespace}:$id"
}

enum class ContentType(val idNamespace: String) {
    ANIME("a"),
    MANGA("m"),
    NOVEL("m") // light novels share MAL's manga ID space
}