/*
 * AnimeRec - Anime Recommendation App
 * Copyright (C) 2025 Shuvam Banerji Seal
 * Licensed under the MIT License.
 */
package com.animerec.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests for ApiResponseCache.
 *
 * Verifies S6 (thread safety) and S10 (size cap / LRU eviction) fixes.
 */
class ApiResponseCacheTest {

    @Test
    fun `get returns null for missing key`() {
        val cache = ApiResponseCache(maxSize = 10)
        assertThat(cache.get<String>("missing")).isNull()
    }

    @Test
    fun `put and get round trip`() {
        val cache = ApiResponseCache(maxSize = 10)
        cache.put("k", "v", expirationMs = 60_000)
        assertThat(cache.get<String>("k")).isEqualTo("v")
    }

    @Test
    fun `get returns null for expired entry and removes it`() {
        val cache = ApiResponseCache(maxSize = 10)
        cache.put("k", "v", expirationMs = -1) // already expired
        assertThat(cache.get<String>("k")).isNull()
        assertThat(cache.size()).isEqualTo(0)
    }

    @Test
    fun `remove deletes a key`() {
        val cache = ApiResponseCache(maxSize = 10)
        cache.put("k", "v", 60_000)
        cache.remove("k")
        assertThat(cache.size()).isEqualTo(0)
    }

    @Test
    fun `clear removes all entries`() {
        val cache = ApiResponseCache(maxSize = 10)
        cache.put("a", 1, 60_000)
        cache.put("b", 2, 60_000)
        cache.clear()
        assertThat(cache.size()).isEqualTo(0)
    }

    @Test
    fun `cache respects max size with LRU eviction`() {
        val cache = ApiResponseCache(maxSize = 3)
        cache.put("a", 1, 60_000)
        cache.put("b", 2, 60_000)
        cache.put("c", 3, 60_000)
        // Access 'a' to mark it recently used
        assertThat(cache.get<Int>("a")).isEqualTo(1)
        // Insert 'd' - should evict least-recently-used ('b')
        cache.put("d", 4, 60_000)
        assertThat(cache.size()).isEqualTo(3)
        assertThat(cache.get<Int>("b")).isNull()
        assertThat(cache.get<Int>("a")).isEqualTo(1)
        assertThat(cache.get<Int>("c")).isEqualTo(3)
        assertThat(cache.get<Int>("d")).isEqualTo(4)
    }

    @Test
    fun `cache is thread safe under concurrent puts`() {
        val cache = ApiResponseCache(maxSize = 1000)
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(100)
        try {
            repeat(100) { i ->
                pool.execute {
                    cache.put("k$i", "v$i", 60_000)
                    cache.get<String>("k$i")
                    latch.countDown()
                }
            }
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
            // All 100 entries should be retrievable.
            var count = 0
            repeat(100) { i ->
                if (cache.get<String>("k$i") != null) count++
            }
            assertThat(count).isEqualTo(100)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `concurrent get and put do not corrupt cache`() {
        val cache = ApiResponseCache(maxSize = 100)
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(200)
        try {
            repeat(100) { i ->
                pool.execute {
                    cache.put("k$i", "v$i", 60_000)
                    latch.countDown()
                }
                pool.execute {
                    cache.get<String>("k$i")
                    latch.countDown()
                }
            }
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `clearExpired removes only expired entries`() {
        val cache = ApiResponseCache(maxSize = 10)
        cache.put("alive", 1, 60_000)
        cache.put("dead", 2, -1)
        cache.clearExpired()
        assertThat(cache.get<Int>("alive")).isEqualTo(1)
        assertThat(cache.get<Int>("dead")).isNull()
    }
}
