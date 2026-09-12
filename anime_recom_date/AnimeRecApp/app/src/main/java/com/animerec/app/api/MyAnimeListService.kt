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
package com.animerec.app.api

import com.animerec.app.api.response.AnimeDetailsResponse
import com.animerec.app.api.response.AnimeListResponse
import com.animerec.app.api.response.MangaDetailsResponse
import com.animerec.app.api.response.MangaListResponse
import com.animerec.app.api.response.RankingResponse
import com.animerec.app.api.response.RecommendationsResponse
import com.animerec.app.api.response.SeasonalAnimeResponse
import com.animerec.app.api.response.UserAnimeListResponse
import com.animerec.app.api.response.UserMangaListResponse
import com.animerec.app.api.response.UserProfileResponse
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit service interface for MyAnimeList API.
 */
interface MyAnimeListService {
    
    // User Profile
    @GET("users/@me")
    suspend fun getUserProfile(
        @Query("fields") fields: String = "id,name,gender,location,picture,anime_statistics"
    ): Response<UserProfileResponse>
    
    // Anime Details
    @GET("anime/{anime_id}")
    suspend fun getAnimeDetails(
        @Path("anime_id") animeId: Int,
        @Query("fields") fields: String
    ): Response<AnimeDetailsResponse>
    
    // Manga Details
    @GET("manga/{manga_id}")
    suspend fun getMangaDetails(
        @Path("manga_id") mangaId: Int,
        @Query("fields") fields: String
    ): Response<MangaDetailsResponse>
    
    // Anime List (Search)
    @GET("anime")
    suspend fun searchAnime(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("fields") fields: String,
        @Query("nsfw") nsfw: Boolean = false
    ): Response<AnimeListResponse>
    
    // Manga List (Search)
    @GET("manga")
    suspend fun searchManga(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("fields") fields: String,
        @Query("nsfw") nsfw: Boolean = false
    ): Response<MangaListResponse>
    
    // Anime Rankings
    @GET("anime/ranking")
    suspend fun getAnimeRankings(
        @Query("ranking_type") rankingType: String = "all", // all, airing, upcoming, tv, ova, movie, special, bypopularity, favorite
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("fields") fields: String
    ): Response<RankingResponse>
    
    // Manga Rankings
    @GET("manga/ranking")
    suspend fun getMangaRankings(
        @Query("ranking_type") rankingType: String = "all", // all, manga, novels, oneshots, doujin, manhwa, manhua, bypopularity, favorite
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("fields") fields: String
    ): Response<RankingResponse>
    
    // Seasonal Anime
    @GET("anime/season/{year}/{season}")
    suspend fun getSeasonalAnime(
        @Path("year") year: Int,
        @Path("season") season: String, // winter, spring, summer, fall
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("fields") fields: String,
        @Query("sort") sort: String = "anime_score" // anime_score, anime_num_list_users
    ): Response<SeasonalAnimeResponse>
    
    // Anime Recommendations
    @GET("anime/suggestions")
    suspend fun getAnimeRecommendations(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("fields") fields: String
    ): Response<RecommendationsResponse>
    
    // User Anime List
    @GET("users/@me/animelist")
    suspend fun getUserAnimeList(
        @Query("status") status: String? = null, // watching, completed, on_hold, dropped, plan_to_watch
        @Query("sort") sort: String = "list_score", // list_score, list_updated_at, anime_title, anime_start_date
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("fields") fields: String
    ): Response<UserAnimeListResponse>
    
    // User Manga List
    @GET("users/@me/mangalist")
    suspend fun getUserMangaList(
        @Query("status") status: String? = null, // reading, completed, on_hold, dropped, plan_to_read
        @Query("sort") sort: String = "list_score", // list_score, list_updated_at, manga_title, manga_start_date
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("fields") fields: String
    ): Response<UserMangaListResponse>
    
    // Update Anime Status
    @FormUrlEncoded
    @PATCH("anime/{anime_id}/my_list_status")
    suspend fun updateAnimeStatus(
        @Path("anime_id") animeId: Int,
        @Field("status") status: String? = null, // watching, completed, on_hold, dropped, plan_to_watch
        @Field("score") score: Int? = null, // 0-10
        @Field("num_watched_episodes") numWatchedEpisodes: Int? = null,
        @Field("is_rewatching") isRewatching: Boolean? = null,
        @Field("start_date") startDate: String? = null, // YYYY-MM-DD
        @Field("finish_date") finishDate: String? = null, // YYYY-MM-DD
        @Field("priority") priority: Int? = null, // 0-2
        @Field("num_times_rewatched") numTimesRewatched: Int? = null,
        @Field("rewatch_value") rewatchValue: Int? = null, // 0-5
        @Field("tags") tags: String? = null,
        @Field("comments") comments: String? = null
    ): Response<Any>
    
    // Update Manga Status
    @FormUrlEncoded
    @PATCH("manga/{manga_id}/my_list_status")
    suspend fun updateMangaStatus(
        @Path("manga_id") mangaId: Int,
        @Field("status") status: String? = null, // reading, completed, on_hold, dropped, plan_to_read
        @Field("score") score: Int? = null, // 0-10
        @Field("num_volumes_read") numVolumesRead: Int? = null,
        @Field("num_chapters_read") numChaptersRead: Int? = null,
        @Field("is_rereading") isRereading: Boolean? = null,
        @Field("start_date") startDate: String? = null, // YYYY-MM-DD
        @Field("finish_date") finishDate: String? = null, // YYYY-MM-DD
        @Field("priority") priority: Int? = null, // 0-2
        @Field("num_times_reread") numTimesReread: Int? = null,
        @Field("reread_value") rereadValue: Int? = null, // 0-5
        @Field("tags") tags: String? = null,
        @Field("comments") comments: String? = null
    ): Response<Any>
    
    // Remove Anime from List (undo an accidental add)
    @DELETE("anime/{anime_id}/my_list_status")
    suspend fun deleteAnimeListStatus(
        @Path("anime_id") animeId: Int
    ): Response<Unit>
    
    // Remove Manga from List (undo an accidental add)
    @DELETE("manga/{manga_id}/my_list_status")
    suspend fun deleteMangaListStatus(
        @Path("manga_id") mangaId: Int
    ): Response<Unit>
}
