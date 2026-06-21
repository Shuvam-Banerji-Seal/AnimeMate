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

---

## [1.1.4] - 2026-06-13

### "No browser available" hotfix

**The reported bug:** v1.1.3 surfaced a "No browser available" error
to users on devices/emulators where Chrome was clearly installed.
The login button would tap, the status text would say "Opening
browser…", and then immediately flip to a `no_browser` error.

**Root cause:** I had set `Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER`
on the launch intent in v1.1.3, reasoning (incorrectly) that it
would force the system to use a real browser rather than a
WebView shim. The actual semantics of that flag are the **opposite**:
*"do not deliver this Intent to a component that is a browser"*
(per the AOSP docs). The only apps that can handle
`https://myanimelist.net/...` are browsers, so the system filtered
them all out and `startActivity` threw `ActivityNotFoundException`
on every device.

The v1.1.2 release had the same flag, but it was masked by the
Custom Tabs primary path: on real devices Chrome Custom Tabs
handled the auth, so the system-browser fallback with the broken
flag was rarely exercised.

### Fix

- **Removed** `Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER` from the
  launch intent. The flag was a misunderstanding of the Android
  intent-flag contract.
- **Added a 3-strategy fallback chain** in `OAuthLauncher.launch`:
  1. `Intent.ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK` (recommended)
  2. `Intent.createChooser(...)` (force system picker UI)
  3. `Intent.ACTION_VIEW` without `NEW_TASK` (last resort)
  Each strategy is wrapped in a `tryStartActivity` helper that
  swallows `ActivityNotFoundException` and `SecurityException`
  (some OEM ROMs throw the latter when intents try to leave the
  app), then moves on to the next strategy.
- **Added a pre-flight check** — `hasAnyBrowser(context)` queries
  `PackageManager.queryIntentActivities` for a test `https://`
  URL. If zero resolvers exist, the launcher skips all 3
  strategies and surfaces a structured `no_browser` error
  immediately, with a user-friendly message ("please install
  Chrome, Firefox, or any other browser and try again").
- **Surfaced a meaningful error message** instead of just the
  generic "no_browser" code — the user now sees instructions on
  what to do next.
- **Updated `LoginFragment.initiateLogin`** to capture the
  launcher's `Boolean` return value. If launch fails, the loading
  indicator is dismissed immediately so the user can retry
  instead of being stuck on "Opening browser…".

### New tests (regression coverage for the bug)

- **`OAuthLauncherTest`** (8 tests) — Robolectric + MockK
  - `launch posts no_browser error when no resolver exists for https`
  - `launch returns true when first strategy succeeds`
  - `launch falls back to createChooser when strategy 1 throws`
  - `launch falls back to plain ACTION_VIEW when strategies 1 and 2 throw`
  - `launch reports no_browser when all 3 strategies throw`
  - `launch does not set FLAG_ACTIVITY_REQUIRE_NON_BROWSER` (regression test for this exact bug)
  - `launch strategy 1 sets NEW_TASK so it can launch from a fragment context`
  - `launch posts no_browser error when no resolver exists for https` (duplicate of #1 for the pre-flight path)

**Total: 68 passing tests, 7 skipped, 0 failing.**

### Build

- Bumped `versionCode = 6`, `versionName = "1.1.4"`.
- APKs: `AnimeMate-1.1.4-release.apk` (4.5 MB) and
  `AnimeMate-1.1.4-debug-debug.apk` (22 MB).
- No new dependencies.

### Lessons

`FLAG_ACTIVITY_REQUIRE_NON_BROWSER` is a **misleadingly-named flag**.
Despite the "NON_BROWSER" suffix, it does not mean "use a real
browser" — it means "do not deliver to a browser at all". The
correct flag for "use a real browser" is to simply not set
`FLAG_ACTIVITY_REQUIRE_NON_BROWSER` and let the system pick the
best resolver, falling back to `Intent.createChooser` if the
system can't pick one.

This is now covered by a regression test in
`OAuthLauncherTest.launch does not set FLAG_ACTIVITY_REQUIRE_NON_BROWSER` —
the test will fail loudly if anyone re-introduces the flag.

---

## [1.1.5] - 2026-06-13

### Login flow hard-fix — "still broken" after v1.1.4

v1.1.4 fixed the browser-launch path, but the user reported the
login was *still* broken. Audit found two latent bugs in the
deep-link return path:

### Bug A: `runBlocking` on the main thread + lifecycle races

`OAuthCallbackActivity.handleIntent` used to call
`runBlocking { authManager.exchangeCodeForTokens(code, codeVerifier) }`
on the main thread. This blocked the activity for the duration
of the network request. While blocked, lifecycle events were
queued but couldn't run. On slow networks or when OkHttp's
interceptor `runBlocking` was already in flight, the activity
could time out. `bringMainToFront()` could be called before the
token exchange completed — the user landed back on MainActivity
with no tokens.

**Fix:** Replaced `runBlocking` with a per-activity
`CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`.
Token exchange runs on `Dispatchers.IO`. `bringMainToFront()`
is called *after* the exchange completes. Scope cancelled in
`onDestroy()`.

### Bug B: Bus event lost when process is killed mid-flight

If Android killed the process between the browser redirect and
`OAuthCallbackActivity` finishing, the bus event was lost. The
user would re-open the app, see the splash, and if timing was
unlucky, land on the login fragment with no indication that
auth had just succeeded.

**Fix:** OAuthCallbackActivity now writes a
`pending_auth_success` flag to `SecureStorage` after a
successful token exchange, *in addition* to posting the bus
event. `LoginFragment.onViewCreated` and `onResume` both check
the flag, clear it, and re-post the bus event. This survives
process death because EncryptedSharedPreferences is persisted
to disk.

### Bug C: `bringMainToFront` could fail silently

The previous version used `packageManager.getLaunchIntentForPackage`.
If the launch intent was null (rare ROMs), the user silently
stayed in the browser.

**Fix:** Try `setClassName("com.animerec.app.ui.MainActivity")`
first (explicit class, always works), then fall back to
`getLaunchIntentForPackage`. Log loudly at each step.

### Other improvements

- **`OAuthCallbackActivity.onNewIntent`** now calls `setIntent(intent)`.
- **`LoginFragment.onResume`** also checks the pending flag.
- **Better logging** at every step.

### Tests

**72 passing tests, 10 skipped, 0 failing.**

New `PendingAuthFlagTest` (3 tests, all skipped on JVM Robolectric
because AndroidKeyStore isn't available; runs on real device or
under `connectedAndroidTest`):
- Default value is `false`
- Written value reads back as `true`
- Survives "process restart" (re-instantiation of SecureStorage)

### Build

- Bumped `versionCode = 7`, `versionName = "1.1.5"`.
- APKs: `AnimeMate-1.1.5-release.apk` (4.5 MB) and
  `AnimeMate-1.1.5-debug-debug.apk` (22 MB).
- No new dependencies.

---

## [1.1.6] - 2026-06-22

### "No browser available" — actual root cause fix

**The reported bug (post-v1.1.5):** "it shows no browser is available on the device while i have browsers."

**The actual root cause:** v1.1.4 introduced a pre-flight `hasAnyBrowser(context)` check that called `PackageManager.queryIntentActivities()` for the `https://` scheme. On **Android 11+ (API 30+)**, this call returns **empty** for every browser, even when Chrome, Firefox, Samsung Internet, etc. are installed. This is because of the [package visibility rules](https://developer.android.com/training/package-visibility) introduced in Android 11: an app can only see other apps that match a declared `<queries>` entry in its manifest, are in the same package signature, or are explicit OS exemptions.

Without `<queries>`, my pre-flight check said "no browsers found" on every Android 11+ device. The "no_browser" error was a false positive.

### Fix

- **Removed** the pre-flight `hasAnyBrowser` check. The whole point of v1.1.1 was that this check is unnecessary — `Intent.ACTION_VIEW` is dispatched to the user's default browser, and if no browser is installed (vanilla AOSP emulator), `startActivity` throws `ActivityNotFoundException` which we already catch. The check was over-engineering, period.
- **Simplified** `OAuthLauncher` back to v1.1.1's straightforward shape: one `Intent.ACTION_VIEW` call, one try/catch, done.
- **Added `<queries>`** to `AndroidManifest.xml` declaring the `https://`, `http://`, and `animerec://auth` query intents. This is the manifest-level fix for package visibility. Even though the pre-flight check is gone, this declaration is good practice — any future code that needs to query for browsers (e.g. share-target selection, "open in browser" feature) will now work correctly on Android 11+.
- **Improved error messages**:
  - `no_browser` — "No browser is available to complete the sign-in. Please install Chrome, Firefox, or any other browser and try again."
  - `security_error` — "System blocked the browser launch. Please check your app permissions and try again." (handles the rare case where an OEM ROM throws `SecurityException` on outbound intents)

### The login flow now

1. User taps the **Login with MyAnimeList** button (or any of the 4 provider quick-buttons).
2. `OAuthLauncher.launch()` builds an `Intent.ACTION_VIEW` for the MAL authorization URL and calls `startActivity`.
3. The OS dispatches to the user's default browser (Chrome, Firefox, Samsung Internet, etc.).
4. The user logs in inside their browser. For third-party providers (Google / Apple / Facebook / X), MAL routes through the provider's own OAuth — the user never enters their provider password in AnimeMate.
5. MAL redirects to `animerec://auth?code=…&state=…` — the OS routes this to `OAuthCallbackActivity` (declared in the manifest with its own intent-filter).
6. `OAuthCallbackActivity` verifies the CSRF state, exchanges the code for tokens, writes a `pending_auth_success` flag to `SecureStorage` (v1.1.5), posts to `AuthCallbackBus`, and brings `MainActivity` to the front.
7. `LoginFragment` checks the pending flag and the bus, navigates to home.

### Tests

**71 passing tests, 10 skipped, 0 failing.**

`OAuthLauncherTest` rewritten for the simpler launcher:

- `launch invokes startActivity with ACTION_VIEW and the auth URL`
- `launch sets FLAG_ACTIVITY_NEW_TASK so it works from a fragment context`
- `launch does NOT set FLAG_ACTIVITY_REQUIRE_NON_BROWSER (v1.1.3 regression test)` — still pinned so nobody reintroduces the bad flag
- `launch preserves the full URL in the launch intent`
- `launch returns true on successful startActivity`
- `launch posts no_browser error when startActivity throws ActivityNotFoundException`
- `launch posts security_error when startActivity throws SecurityException`

### Build

- Bumped `versionCode = 8`, `versionName = "1.1.6"`.
- APKs: `AnimeMate-1.1.6-release.apk` (4.5 MB) and `AnimeMate-1.1.6-debug-debug.apk` (22 MB).
- No new dependencies.
- Manifest: added `<queries>` block at the top of the manifest (3 intent entries).
