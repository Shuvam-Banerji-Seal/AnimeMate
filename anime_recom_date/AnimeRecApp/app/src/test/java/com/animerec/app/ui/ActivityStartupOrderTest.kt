/*
 * AnimeRec - Anime Recommendation App
 * Copyright (C) 2025 Shuvam Banerji Seal
 * Licensed under the MIT License.
 */
package com.animerec.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-level regression guard for the cold-start UI bug that shipped from
 * 1.0 through 1.2.0.
 *
 * `AppCompatActivity` bootstraps its delegate from an `OnContextAvailableListener`
 * that `ComponentActivity` dispatches *inside* `super.onCreate()` — that is
 * where `installViewFactory()` and `delegate.onCreate()` → `applyDayNight()`
 * run. Touching `getWindow()`, `getTheme()` or `getResources()` before that
 * call resolves the theme and installs the decor against the pre-night-mode
 * state, leaving the window half-configured. The visible result was a UI that
 * came up wrong on every fresh launch and only looked right after toggling the
 * theme, because the toggle forced a recreate.
 *
 * This is checked against the source text rather than at runtime: the failure
 * is an *ordering* property of a method that a unit test cannot observe
 * directly, and the codebase already uses this style of guard (see
 * `OAuthLauncherTest`, which pins that a specific intent flag is never
 * re-introduced).
 */
class ActivityStartupOrderTest {

    private fun sourceFile(relativePath: String): File {
        // Gradle runs unit tests with the module directory as the working
        // directory, but don't rely on it — walk up until the repo layout
        // appears, so the test survives being run from elsewhere.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            val nested = File(dir, "app/$relativePath")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relativePath from ${System.getProperty("user.dir")}")
    }

    private val mainActivitySource: String by lazy {
        sourceFile("src/main/java/com/animerec/app/ui/MainActivity.kt").readText()
    }

    /**
     * The source with comments removed.
     *
     * Whole-file checks must run against code only — the comments in
     * MainActivity deliberately quote the old broken calls to explain why they
     * were removed, and matching those would make this test fail on its own
     * documentation.
     */
    private val mainActivityCode: String by lazy {
        mainActivitySource
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun `super onCreate is the first statement in MainActivity onCreate`() {
        val body = onCreateBodyBeforeSuper(mainActivityCode)

        val offendingLines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }

        assertThat(offendingLines).isEmpty()
    }

    @Test
    fun `nothing touches window theme or resources before super onCreate`() {
        val body = onCreateBodyBeforeSuper(mainActivityCode)

        for (forbidden in listOf("window", "theme", "resources", "getWindow", "getTheme", "setContentView")) {
            assertThat(body).doesNotContain(forbidden)
        }
    }

    @Test
    fun `the decorView requestApplyInsets workaround is gone`() {
        // This hack existed only to paper over the premature decor install.
        // Insets are now handled properly via setOnApplyWindowInsetsListener;
        // if this string comes back, the real fix has probably been reverted.
        assertThat(mainActivityCode).doesNotContain("decorView.post")
    }

    @Test
    fun `MainActivity consumes window insets rather than relying on decor fitting`() {
        // Apps targeting SDK 35 are laid out edge-to-edge unconditionally on
        // Android 15, where setDecorFitsSystemWindows(true), statusBarColor and
        // navigationBarColor are all no-ops. Something must actually read the
        // insets, or content renders under the system bars.
        assertThat(mainActivityCode).contains("setOnApplyWindowInsetsListener")
        assertThat(mainActivityCode).doesNotContain("setDecorFitsSystemWindows(window, true)")
    }

    /** Everything between `fun onCreate(...) {` and the `super.onCreate(` call. */
    private fun onCreateBodyBeforeSuper(source: String): String {
        val marker = "override fun onCreate(savedInstanceState: Bundle?) {"
        val start = source.indexOf(marker)
        assertThat(start).isNotEqualTo(-1)

        val superCall = source.indexOf("super.onCreate(", start)
        assertThat(superCall).isNotEqualTo(-1)

        return source.substring(start + marker.length, superCall)
    }
}
