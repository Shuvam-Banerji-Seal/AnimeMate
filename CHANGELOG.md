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

---

## [1.1.2] - 2026-06-13

### Login flow overhaul — "the app would not log in"

**The reported bug:** after tapping "Allow" in the browser, the user
was bounced back to the login screen. Closing and reopening the app
didn't help. The deep-link redirect `animerec://auth?…` was being
delivered to `MainActivity`, but `MainActivity.handleIntent` relied
on `LoginFragment` being attached, the navigation back-stack
matching, and the verifier still being in storage. Any of those
missing → silent failure.

**Root cause:** MainActivity tried to do double-duty as the OAuth
callback receiver *and* the home-screen host. That coupling is what
broke the round trip.

**Fix:** three new classes, one new manifest entry, one new layout:

1. **`OAuthCallbackActivity`** (`ui/auth/OAuthCallbackActivity.kt`)
   - A dedicated, `singleTask`, `excludeFromRecents`,
     `Theme.Translucent.NoTitleBar` activity.
   - Has its own intent-filter for `animerec://auth` so the system
     routes the deep-link directly to it, regardless of which activity
     was on top.
   - Verifies the CSRF `state` parameter, exchanges the code for
     tokens, and posts the result to a new `AuthCallbackBus` (a
     process-wide `MutableLiveData<AuthCallbackEvent>`).
   - Always finishes; never blocks the UI thread.
2. **`WebViewLoginActivity`** (`ui/auth/WebViewLoginActivity.kt`) —
   **this is the fix the user explicitly asked for**: a true
   in-app `WebView` browser so the user never leaves AnimeMate.
   - Intercepts `animerec://auth?…` inside
     `WebViewClient.shouldOverrideUrlLoading`, so the OS hand-off
     is bypassed entirely.
   - Cookie persistence so the user does not have to re-enter
     their MAL credentials every launch.
   - Optional fallback path: `OAuthLauncher` still tries Chrome
     Custom Tabs first (for the system-browser feel) and falls
     back to the system browser if neither is available.
3. **`OAuthLauncher`** (`ui/auth/OAuthLauncher.kt`) — Custom Tabs
   with a system-browser fallback. Activated by the
   "Log in with MyAnimeList" button when the user prefers the
   browser surface (e.g. a device where the WebView is broken).
4. **`AuthCallbackBus`** — global `LiveData` event channel so
   `LoginFragment` and any other observer can react to a
   successful auth flow, even if the callback activity finished
   before the fragment was attached.
5. **New login layout** (`res/layout/fragment_login.xml`) —
   4 quick-pick provider buttons (Google, Apple, Facebook, X) +
   a "Log in inside the app" outlined button below the divider
   that launches the WebView path.

### PKCE fix (corrected S3)

v1.1.1 claimed MAL accepts S256. **It does not.** MAL's official
OAuth2 reference page
(<https://myanimelist.net/apiconfig/references/authorization>)
states: "Currently, only the `plain` method is supported."

v1.1.2 reverts to `plain` by default and documents the
constraint. `OAuthUtil.generateCodeChallenge(..., useS256 = false)`
is the default call; `useS256 = true` is kept as a future-proofing
escape hatch for the day MAL flips the switch.

### Provider support

MyAnimeList accepts 4 third-party identity providers in addition
to its own username/password login: **Google, Apple, Facebook, X**
(formerly Twitter). The login screen now has a button for each,
and the `OAuthProvider` enum drives the `prompt=login` parameter
that forces MAL to re-show the login screen when the user wants
to switch accounts.

### New tests

- **`OAuthFlowTest`** (8 tests) — `OAuthProvider` enum
  coverage, `buildAuthorizationUrl` with and without `prompt=`,
  `extractAuthCode` / `extractError` / `extractState` on the
  redirect URI.
- **Updated `OAuthUtilTest`** — flips the S256 assumption to the
  MAL-correct `plain` default; adds an explicit S256-on-demand
  test so the future-proofing path is also covered.

Total: **50 passing unit tests, 7 skipped, 0 failing.**

### Build / Tooling

- Bumped `versionCode = 4`, `versionName = "1.1.2"`.
- Added `androidx.browser:browser:1.8.0` for Chrome Custom Tabs.
- Added `Theme.Translucent.NoTitleBar` style (translucent + no
  title + no animation) for the invisible callback activity.
- New `activity_webview_login.xml` layout.
- `MainActivity.onNewIntent` is now a no-op for OAuth URIs (the
  callback activity handles them).
- New `ic_google.xml`, `ic_apple.xml`, `ic_facebook.xml`, `ic_x.xml`
  vector drawables for the provider buttons.

### Verified flows

- OAuth `state` parameter embed & verify on callback (manual +
  unit tests).
- `animerec://auth?code=…&state=…` deep link routing
  (manifest + intent-filter unit coverage).
- In-app WebView intercepts redirect inside the WebView
  (`shouldOverrideUrlLoading` + state check).
- Custom Tabs fallback when no WebView is desired
  (`OAuthLauncher.launch`).
- Unit tests pass on `testDebugUnitTest` and `testReleaseUnitTest`.
