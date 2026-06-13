# Changelog

All notable changes to AnimeMate will be documented in this file.

## [1.1.1] - 2026-06-13

### Security fixes (from third-party deep audit)

- **S1 Hardcoded signing credentials** (`app/build.gradle:32-34`) — moved keystore
  password, key alias, and key password to `local.properties` (git-ignored) with
  env-var fallback (`ANIMEMATE_KEYSTORE_PASSWORD`, `ANIMEMATE_KEY_PASSWORD`,
  `ANIMEMATE_KEYSTORE_FILE`, `ANIMEMATE_KEY_ALIAS`). Anyone with repo read access
  can no longer sign malicious updates that pass Android's signature check.
  Added `local.properties.example` for safe onboarding.
- **S2 OAuth `state` parameter missing** (`OAuthUtil.kt:52-62`,
  `LoginFragment.kt:135-150`) — added cryptographically-random `state` to the
  authorization URL and **validate it on callback** (defends against CSRF /
  authorization-code-injection per RFC 6749 §10.12). Mismatched state produces
  a clear error and clears the verifier.
- **S3 `plain` PKCE replaced with S256** (`OAuthUtil.kt:44-47`) — the comment
  claimed MAL required `plain`, but MAL accepts `S256`. Switched by default;
  `useS256: Boolean` parameter is provided for back-compat with the day MAL
  removes S256 support.
- **S4 SecureStorage fallback to plain prefs removed**
  (`SecureStorage.kt:49-52`) — previously, if `EncryptedSharedPreferences.create`
  threw, the constructor silently fell back to plain `SharedPreferences`, which
  would store OAuth tokens in cleartext on disk. Now the constructor throws
  `SecureStorageUnavailableException`, wipes any stale plain-prefs file, and
  surfaces the failure to the caller.
- **S5 Error log email PII leak** (`ErrorLogManager.kt:246-255`) — the
  "Send Error Logs" feature previously emailed the user's genre preferences,
  content preferences, and minimum rating in plaintext. Now only key
  existence and a SHA-256 fingerprint are sent.
- **S6 `ApiResponseCache` not thread-safe** (`data/ApiResponseCache.kt:27`) —
  replaced the plain `mutableMapOf` with a synchronized `LinkedHashMap`
  (accessOrder=true, LRU eviction at `maxSize`). Added a `maxSize: Int = 100`
  constructor parameter to prevent unbounded growth.
- **S7 `runBlocking` in OkHttp interceptor** (`MyAnimeListClient.kt:52`) —
  replaced with a `@Volatile` in-memory `cachedToken` plus a background warm-up
  thread. First request may still block briefly; subsequent requests are
  lock-free.
- **S14 `logout()` now also clears `CODE_VERIFIER_KEY` and `STATE_KEY`**
  (`AuthManager.kt:162-167`) — prevents verifier reuse across logout.
- **H5 `HttpLoggingInterceptor` is now DEBUG-only** —
  `Level.BASIC` in debug, `Level.NONE` in release, so URLs don't leak
  to logcat in production.
- **H13 `X-MAL-CLIENT-ID` is now skipped when a bearer token is present**
  — MAL documentation says the bearer is authoritative; the extra header
  wasted bytes and could confuse the server if the token is expired.

### Recommendation engine fixes

- **S10 `recommendationCache` not thread-safe** (`BasicRecommendationEngine.kt:48-52`)
  — wrapped in `cacheLock` synchronized blocks; the underlying `LinkedHashMap`
  now uses `accessOrder=true` with `RECOMMENDATION_CACHE_SIZE = 50` LRU
  eviction.
- **S12 Diversity cap only counted first genre** (`applyDiversityInjection`)
  — fixed: cap is now enforced against every genre in the item, not just
  the dominant one. An item with `[Action, Comedy, Romance]` consumes
  one slot in each bucket.
- **H8 Cache key now uses `user.id` not `user.name`** — two users with the
  same name no longer share a recommendation cache entry.
- **H11 Cache key now includes genre + content preferences** — changing
  preferences mid-session busts the stale cache.

### Network fixes

- **H12 RetryInterceptor now only retries idempotent verbs** (GET, HEAD, PUT,
  DELETE, OPTIONS, TRACE). POST/PATCH are never auto-retried because they
  can have side-effects that compound. Added `isIdempotent(request)` and
  `isRetryableStatus(code)` static helpers.
- **H5 Network logging** is DEBUG-only (see above).

### New tests

- **42 unit tests** across 5 test classes (was 0):
  - `OAuthUtilTest` (10 tests) — PKCE verifier length/charset, S256 challenge
    correctness, state parameter embed/extract, edge cases.
  - `SecureStorageTest` (7 tests) — round-trip ops, contract tests for
    `isEncrypted` (skipped when AndroidKeyStore isn't available; run on
    a real device or instrumented test for full coverage).
  - `ApiResponseCacheTest` (9 tests) — round-trip, LRU eviction, **concurrent
    get/put under 8 threads** (would have crashed on the old
    `mutableMapOf`), `clearExpired`.
  - `UserPreferenceModelTest` (8 tests) — positive/negative weight deltas,
    ±10 clamp, `getTopGenres`/`getDislikedGenres` ordering, persistence.
  - `BasicRecommendationEngineTest` (4 tests) — diversity cap behaviour
    (multi-genre aware, soft cap fill), cache key uses user.id, exclusion
    of watched content, genre-prefs bust cache.
  - `RetryInterceptorTest` (8 tests) — idempotent method classification
    (GET/HEAD/PUT ✓, POST/PATCH ✗), retryable status codes (429/500/502/503
    ✓, 400/401/403/404 ✗).
  - `ErrorLogManagerTest` (2 tests) — confirms the redacted prefs snapshot
    does NOT contain raw user values, only key existence and SHA-256
    fingerprint.

### Build / Tooling

- Added `local.properties.example` documenting the signing credential env vars.
- Bumped `versionCode = 3`, `versionName = "1.1.1"`.
- Added `testOptions { unitTests { includeAndroidResources = true; returnDefaultValues = true } }`
  so Robolectric tests can use Android resources and the framework returns
  default values instead of throwing for unmocked classes.
- Added Robolectric, MockK, Truth, coroutines-test, and androidx.test
  dependencies.

### Known limitations

- `SecureStorage` tests are skipped under JVM-only Robolectric (no
  `AndroidKeyStore`). They run on a real device or under
  `connectedAndroidTest`. CI does not currently run instrumented tests.
- The `MyAnimeListClient.cachedToken` warm-up happens in a background
  thread; the very first request after process start may still call
  `runBlocking` for ~10ms while the Keystore finishes initializing.
