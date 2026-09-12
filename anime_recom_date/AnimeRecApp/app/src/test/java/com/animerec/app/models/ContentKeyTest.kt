/*
 * AnimeRec - Anime Recommendation App
 * Copyright (C) 2025 Shuvam Banerji Seal
 * Licensed under the MIT License.
 */
package com.animerec.app.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * MyAnimeList numbers anime and manga in separate ID spaces, so a bare `id`
 * does not identify a work. These tests pin the namespacing that everything
 * else (dedupe, exclusion, DiffUtil) depends on.
 */
class ContentKeyTest {

    @Test
    fun `anime and manga with the same id have different keys`() {
        val anime = AnimeContent(id = 1535, title = "Death Note", type = ContentType.ANIME)
        val manga = AnimeContent(id = 1535, title = "Boys Next Door", type = ContentType.MANGA)

        assertThat(anime.contentKey).isNotEqualTo(manga.contentKey)
    }

    @Test
    fun `light novels share the manga id space`() {
        // MAL serves light novels from the manga endpoint, so a novel and a
        // manga with the same ID really are the same entry.
        val novel = AnimeContent(id = 9115, title = "N", type = ContentType.NOVEL)
        val manga = AnimeContent(id = 9115, title = "M", type = ContentType.MANGA)

        assertThat(novel.contentKey).isEqualTo(manga.contentKey)
    }

    @Test
    fun `same type and id produce the same key`() {
        val a = AnimeContent(id = 42, title = "One", type = ContentType.ANIME)
        val b = AnimeContent(id = 42, title = "One (different metadata)", type = ContentType.ANIME, malScore = 9.0)

        assertThat(a.contentKey).isEqualTo(b.contentKey)
    }

    @Test
    fun `distinctBy contentKey keeps colliding ids from different namespaces`() {
        // The regression this guards: `distinctBy { it.id }` over a mixed
        // anime+manga candidate pool silently dropped every manga whose ID
        // matched an anime already in the pool.
        val pool = listOf(
            AnimeContent(id = 1, title = "Anime 1", type = ContentType.ANIME),
            AnimeContent(id = 1, title = "Manga 1", type = ContentType.MANGA),
            AnimeContent(id = 1, title = "Anime 1 dup", type = ContentType.ANIME)
        )

        val deduped = pool.distinctBy { it.contentKey }

        assertThat(deduped).hasSize(2)
        assertThat(deduped.map { it.title }).containsExactly("Anime 1", "Manga 1")
    }

    @Test
    fun `key format is namespace colon id`() {
        assertThat(AnimeContent(id = 7, title = "x", type = ContentType.ANIME).contentKey).isEqualTo("a:7")
        assertThat(AnimeContent(id = 7, title = "x", type = ContentType.MANGA).contentKey).isEqualTo("m:7")
        assertThat(AnimeContent(id = 7, title = "x", type = ContentType.NOVEL).contentKey).isEqualTo("m:7")
    }
}
