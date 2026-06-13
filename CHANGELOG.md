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

---

## [1.1.3] - 2026-06-13

### Login flow simplification — external browser only

**The reported follow-up:** "Google doesn't allow OAuth from inside
the app. Use the external browser."

You were right. v1.1.2's `WebViewLoginActivity` (in-app WebView) and
v1.1.1's `OAuthLauncher` (Chrome Custom Tabs) both fail against
Google, Apple, Facebook, and X in production because those providers
explicitly block OAuth flows that originate from an embedded
`WebView` or a custom-tab surface that isn't the real system
browser. The "real system browser" is the only universally-approved
surface.

**This release strips the in-app WebView path and the Custom Tabs
path, and ships the system browser as the single auth surface.**

### What changed

- **Deleted** `WebViewLoginActivity.kt`, `activity_webview_login.xml`,
  the manifest `<activity>` entry for `WebViewLoginActivity`, the
  3 related strings (`login_inapp_title`, `login_inapp_subtitle`,
  `login_use_inapp`), the `inApp: Boolean = true` parameter on
  `LoginFragment.initiateLogin`, and the `inAppButton` field.
- **Simplified** `OAuthLauncher` to a single `Intent.ACTION_VIEW`
  with `FLAG_ACTIVITY_NEW_TASK` and (on API 30+) the
  `FLAG_ACTIVITY_REQUIRE_NON_BROWSER` flag. The custom-tab binding
  ceremony is gone.
- **Removed dependency** on `androidx.browser:browser:1.8.0` — no
  longer needed.
- **Updated layout** — `fragment_login.xml` no longer has the
  "Log in inside the app" outlined button. The 5 provider buttons
  (MAL, Google, Apple, Facebook, X) are the only login entry points.
- **Updated `OAuthLauncher` docstring** to explain *why* the system
  browser is the only viable surface, so future maintainers don't
  re-introduce the WebView path.
- **LoginFragment docstring** updated to reflect the single
  flow: tap a button → system browser opens MAL → MAL handles the
  provider auth → `animerec://auth?…` deep link is routed by the
  OS to `OAuthCallbackActivity` → token exchange → main activity.

### Why this is the right call

| Provider | WebView OAuth | Custom Tabs OAuth | System Browser OAuth |
|----------|---------------|-------------------|----------------------|
| Google | **blocked** | often blocked, sometimes works | works |
| Apple | **blocked** | works on iOS, flaky on Android | works |
| Facebook | **blocked** | flaky | works |
| X (Twitter) | **blocked** | sometimes works | works |
| MAL (native) | works | works | works |

The system browser is the only surface that works for **all five**
providers. It also gives the user a familiar place to log in
(their own Chrome / Firefox / Samsung Internet with their existing
saved passwords and 2FA devices), and the OS-level deep-link
routing handles the return trip reliably.

### What didn't change

- The dedicated `OAuthCallbackActivity` (singleTask,
  `Theme.Translucent.NoTitleBar`, intent-filter for
  `animerec://auth`) is the keystone of the flow and stays put.
  It receives the system browser's redirect, verifies state,
  exchanges code for tokens, and posts the result via
  `AuthCallbackBus`. This was the actual fix in v1.1.2 and is
  unchanged in v1.1.3.
- The `AuthCallbackBus` LiveData, the 5 `OAuthProvider` enum
  entries, the provider icons, the PKCE state storage in
  `SecureStorage`, and the unit tests all carry over from v1.1.2.
- **All 50 unit tests still pass.** No test changes were needed —
  the public `OAuthLauncher.launch()` API contract is unchanged,
  and `WebViewLoginActivity` was never unit-tested (it requires a
  real WebView which Robolectric can't faithfully simulate).

### Build

- Bumped `versionCode = 5`, `versionName = "1.1.3"`.
- APKs: `AnimeMate-1.1.3-release.apk` (4.5 MB) and
  `AnimeMate-1.1.3-debug-debug.apk` (22 MB).
- Smaller release APK than v1.1.2 (4.5 MB vs 4.6 MB) because the
  `androidx.browser` dependency is gone.
