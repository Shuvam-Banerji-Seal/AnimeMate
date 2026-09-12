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
package com.animerec.app.api.response

import com.animerec.app.models.AnimeContent
import com.animerec.app.models.ContentType
import com.google.gson.annotations.SerializedName

// ============================================
// Main Response Classes
// ============================================

data class AnimeDetailsResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String,
    @SerializedName("main_picture") val mainPicture: Picture? = null,
    @SerializedName("alternative_titles") val alternativeTitles: AlternativeTitles? = null,
    @SerializedName("synopsis") val synopsis: String? = null,
    @SerializedName("mean") val mean: Double? = null,
    @SerializedName("rank") val rank: Int? = null,
    @SerializedName("popularity") val popularity: Int? = null,
    @SerializedName("num_list_users") val numListUsers: Int? = null,
    @SerializedName("media_type") val mediaType: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("genres") val genres: List<Genre>? = null,
    @SerializedName("num_episodes") val numEpisodes: Int? = null,
    @SerializedName("start_season") val startSeason: Season? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("average_episode_duration") val averageEpisodeDuration: Int? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("studios") val studios: List<Studio>? = null,
    @SerializedName("my_list_status") val myListStatus: MyListStatus? = null
) {
    fun toAnimeContent(type: ContentType = ContentType.ANIME): AnimeContent {
        return AnimeContent(
            id = id,
            title = title,
            alternativeTitles = alternativeTitles?.toMap() ?: mapOf(),
            synopsis = synopsis ?: "",
            imageUrl = mainPicture?.large ?: mainPicture?.medium ?: "",
            type = type,
            status = status ?: "",
            genres = genres?.map { it.name } ?: listOf(),
            rating = mean ?: 0.0,
            releaseYear = startSeason?.year,
            episodes = numEpisodes,
            malScore = mean ?: 0.0,
            userScore = myListStatus?.score,
            airingStatus = status ?: "",
            mediaType = mediaType ?: "",
            inWatchlist = myListStatus?.status == "plan_to_watch",
            isCompleted = myListStatus?.status == "completed",
            studios = studios?.map { it.name } ?: listOf(),
            rank = rank,
            popularity = popularity,
            numListUsers = numListUsers ?: 0,
            source = source ?: "",
            averageEpisodeDurationSeconds = averageEpisodeDuration
        )
    }
}

data class MangaDetailsResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String,
    @SerializedName("main_picture") val mainPicture: Picture? = null,
    @SerializedName("alternative_titles") val alternativeTitles: AlternativeTitles? = null,
    @SerializedName("synopsis") val synopsis: String? = null,
    @SerializedName("mean") val mean: Double? = null,
    @SerializedName("rank") val rank: Int? = null,
    @SerializedName("popularity") val popularity: Int? = null,
    @SerializedName("num_list_users") val numListUsers: Int? = null,
    @SerializedName("media_type") val mediaType: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("genres") val genres: List<Genre>? = null,
    @SerializedName("num_volumes") val numVolumes: Int? = null,
    @SerializedName("num_chapters") val numChapters: Int? = null,
    @SerializedName("authors") val authors: List<Author>? = null,
    @SerializedName("my_list_status") val myListStatus: MangaListStatus? = null
) {
    fun toAnimeContent(type: ContentType = ContentType.MANGA): AnimeContent {
        return AnimeContent(
            id = id,
            title = title,
            alternativeTitles = alternativeTitles?.toMap() ?: mapOf(),
            synopsis = synopsis ?: "",
            imageUrl = mainPicture?.large ?: mainPicture?.medium ?: "",
            type = type,
            status = status ?: "",
            genres = genres?.map { it.name } ?: listOf(),
            rating = mean ?: 0.0,
            chapters = numChapters,
            volumes = numVolumes,
            malScore = mean ?: 0.0,
            userScore = myListStatus?.score,
            mediaType = mediaType ?: "",
            inWatchlist = myListStatus?.status == "plan_to_read",
            isCompleted = myListStatus?.status == "completed",
            authors = authors?.mapNotNull { it.displayName() } ?: listOf(),
            rank = rank,
            popularity = popularity,
            numListUsers = numListUsers ?: 0
        )
    }
}

// ============================================
// List Response Classes
// ============================================

data class AnimeListResponse(
    @SerializedName("data") val data: List<AnimeListNode>,
    @SerializedName("paging") val paging: Paging? = null
)

data class MangaListResponse(
    @SerializedName("data") val data: List<MangaListNode>,
    @SerializedName("paging") val paging: Paging? = null
)

data class RankingResponse(
    @SerializedName("data") val data: List<RankingNode>,
    @SerializedName("paging") val paging: Paging? = null
)

data class RecommendationsResponse(
    @SerializedName("data") val data: List<AnimeListNode>,
    @SerializedName("paging") val paging: Paging? = null
)

data class SeasonalAnimeResponse(
    @SerializedName("data") val data: List<AnimeListNode>,
    @SerializedName("paging") val paging: Paging? = null,
    @SerializedName("season") val season: Season? = null
)

data class UserAnimeListResponse(
    @SerializedName("data") val data: List<UserAnimeListNode>,
    @SerializedName("paging") val paging: Paging? = null
)

data class UserMangaListResponse(
    @SerializedName("data") val data: List<UserMangaListNode>,
    @SerializedName("paging") val paging: Paging? = null
)

// ============================================
// Node Wrapper Classes
// ============================================

data class AnimeListNode(
    @SerializedName("node") val node: AnimeDetailsResponse
) {
    fun toAnimeContent(): AnimeContent = node.toAnimeContent()
}

data class MangaListNode(
    @SerializedName("node") val node: MangaDetailsResponse
) {
    fun toAnimeContent(type: ContentType = ContentType.MANGA): AnimeContent = node.toAnimeContent(type)
}

data class RankingNode(
    @SerializedName("node") val node: AnimeDetailsResponse,
    @SerializedName("ranking") val ranking: Ranking? = null
)

data class UserAnimeListNode(
    @SerializedName("node") val node: AnimeDetailsResponse,
    @SerializedName("list_status") val listStatus: MyListStatus? = null
)

data class UserMangaListNode(
    @SerializedName("node") val node: MangaDetailsResponse,
    @SerializedName("list_status") val listStatus: MangaListStatus? = null
)

// ============================================
// Supporting Data Classes
// ============================================

data class Picture(
    @SerializedName("medium") val medium: String? = null,
    @SerializedName("large") val large: String? = null
)

data class AlternativeTitles(
    @SerializedName("synonyms") val synonyms: List<String>? = null,
    @SerializedName("en") val en: String? = null,
    @SerializedName("ja") val ja: String? = null
) {
    fun toMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        en?.let { map["en"] = it }
        ja?.let { map["ja"] = it }
        return map
    }
}

data class Genre(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String
)

data class Season(
    @SerializedName("year") val year: Int,
    @SerializedName("season") val season: String
)

data class Studio(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String
)

data class Author(
    @SerializedName("node") val node: AuthorNode? = null,
    @SerializedName("role") val role: String? = null
)

data class AuthorNode(
    @SerializedName("id") val id: Int,
    @SerializedName("first_name") val firstName: String? = null,
    @SerializedName("last_name") val lastName: String? = null
)

/**
 * "Last, First (Role)" for display, or null when MAL gives us no name at all.
 * Either name part can be missing, so they're joined rather than assumed.
 */
fun Author.displayName(): String? {
    val name = listOfNotNull(
        node?.lastName?.takeIf { it.isNotBlank() },
        node?.firstName?.takeIf { it.isNotBlank() }
    ).joinToString(", ")
    if (name.isBlank()) return null
    val roleLabel = role?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
    return "$name$roleLabel"
}

data class Ranking(
    @SerializedName("rank") val rank: Int
)

data class Paging(
    @SerializedName("previous") val previous: String? = null,
    @SerializedName("next") val next: String? = null
)

// ============================================
// User List Status Classes
// ============================================

data class MyListStatus(
    @SerializedName("status") val status: String? = null,
    @SerializedName("score") val score: Int? = null,
    @SerializedName("num_episodes_watched") val numEpisodesWatched: Int? = null,
    @SerializedName("is_rewatching") val isRewatching: Boolean? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("start_date") val startDate: String? = null,
    @SerializedName("finish_date") val finishDate: String? = null
)

data class MangaListStatus(
    @SerializedName("status") val status: String? = null,
    @SerializedName("score") val score: Int? = null,
    @SerializedName("num_volumes_read") val numVolumesRead: Int? = null,
    @SerializedName("num_chapters_read") val numChaptersRead: Int? = null,
    @SerializedName("is_rereading") val isRereading: Boolean? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("start_date") val startDate: String? = null,
    @SerializedName("finish_date") val finishDate: String? = null
)

// ============================================
// User Profile Response
// ============================================

data class UserProfileResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("picture") val picture: String? = null,
    @SerializedName("gender") val gender: String? = null,
    @SerializedName("location") val location: String? = null,
    @SerializedName("joined_at") val joinedAt: String? = null,
    @SerializedName("anime_statistics") val animeStatistics: UserAnimeStatistics? = null
)

data class UserAnimeStatistics(
    @SerializedName("num_items_watching") val numItemsWatching: Int? = null,
    @SerializedName("num_items_completed") val numItemsCompleted: Int? = null,
    @SerializedName("num_items_on_hold") val numItemsOnHold: Int? = null,
    @SerializedName("num_items_dropped") val numItemsDropped: Int? = null,
    @SerializedName("num_items_plan_to_watch") val numItemsPlanToWatch: Int? = null,
    @SerializedName("num_items") val numItems: Int? = null,
    @SerializedName("num_days_watched") val numDaysWatched: Double? = null,
    @SerializedName("num_days_watching") val numDaysWatching: Double? = null,
    @SerializedName("num_days_completed") val numDaysCompleted: Double? = null,
    @SerializedName("num_days_on_hold") val numDaysOnHold: Double? = null,
    @SerializedName("num_days_dropped") val numDaysDropped: Double? = null,
    @SerializedName("num_times_rewatched") val numTimesRewatched: Int? = null,
    @SerializedName("mean_score") val meanScore: Double? = null,
    @SerializedName("num_episodes") val numEpisodes: Int? = null
)
