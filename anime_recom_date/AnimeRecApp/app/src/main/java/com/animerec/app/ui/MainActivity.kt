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
package com.animerec.app.ui

import android.content.Intent
import android.content.res.Configuration
import android.util.TypedValue
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.animerec.app.R
import com.animerec.app.ui.auth.AuthViewModel
import com.animerec.app.ui.auth.LoginFragment
import com.animerec.app.util.ErrorLogManager
import com.bumptech.glide.Glide
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Main activity that hosts all fragments and manages navigation.
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var authViewModel: AuthViewModel

    /** Suppresses bottom nav listener from triggering navigation during programmatic selection */
    private var suppressBottomNavListener = false

    /**
     * Match the system-bar icon tint to the theme actually in effect.
     *
     * Reads the **resolved** configuration rather than the saved preference:
     * on "follow system" the preference holds MODE_NIGHT_FOLLOW_SYSTEM, which
     * says nothing about what is on screen. `resources.configuration` is
     * authoritative, and by the time this runs (after super.onCreate)
     * AppCompat has applied the delegate's night mode to it.
     */
    private fun syncSystemBarIconAppearance() {
        val isNight = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        ErrorLogManager.logEvent(TAG, "THEME", "Resolved night mode at startup: $isNight")

        // Both themes use a dark status bar and a dark bottom nav, so the
        // icons stay light in either mode.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    /**
     * Lay the app out edge-to-edge and pay the system-bar insets back as
     * padding.
     *
     * Replaces a `setDecorFitsSystemWindows(window, true)` call that ran
     * *before* `super.onCreate()`, plus a `decorView.post { requestApplyInsets() }`
     * hack that tried to make it stick. Neither was reliable, and on Android 15
     * neither does anything at all: apps targeting SDK 35 are laid out
     * edge-to-edge unconditionally, and `setDecorFitsSystemWindows(true)`,
     * `statusBarColor` and `navigationBarColor` are no-ops. Since nothing in
     * the app consumed window insets, content rendered underneath the status
     * bar and the gesture bar — the broken-on-fresh-launch UI this app has
     * shipped with since 1.0.
     *
     * Opting in to edge-to-edge on every API level, instead of only where the
     * platform forces it, keeps this to one code path.
     */
    private fun applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root: View = findViewById(R.id.root)
        val statusScrim: View = findViewById(R.id.status_bar_scrim)

        // Single source of truth for the bar colour: the theme's own
        // android:statusBarColor, which values/ and values-night/ already
        // define. Safe to resolve here because super.onCreate has run.
        val barColor = TypedValue().let { tv ->
            if (theme.resolveAttribute(android.R.attr.statusBarColor, tv, true)) tv.data else null
        }
        if (barColor != null) statusScrim.setBackgroundColor(barColor)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            // Horizontal insets matter in landscape and on cutout devices.
            root.setPadding(bars.left, 0, bars.right, 0)

            // A scrim sized to the status bar preserves the themed bar colour
            // that `window.statusBarColor` used to provide.
            if (statusScrim.layoutParams.height != bars.top) {
                statusScrim.layoutParams = statusScrim.layoutParams.apply { height = bars.top }
            }

            // Let the bottom nav's own background extend behind the gesture
            // bar rather than leaving a strip of bare window background.
            bottomNav.setPadding(0, 0, 0, bars.bottom)

            // Returned unconsumed: child screens (e.g. search, which needs the
            // IME inset) still have to see these.
            windowInsets
        }

        ViewCompat.requestApplyInsets(root)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // super.onCreate FIRST — nothing may touch getWindow(), getTheme() or
        // getResources() before it. AppCompatActivity bootstraps its delegate
        // from an OnContextAvailableListener that ComponentActivity dispatches
        // *inside* super.onCreate(); that is where installViewFactory() and
        // delegate.onCreate() -> applyDayNight() run. Resolving the theme or
        // forcing the decor to be installed ahead of that leaves the window
        // half-configured against the pre-night-mode state — which is why the
        // UI came up wrong on a cold start and only looked right once a theme
        // toggle forced a recreate.
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize view model
        authViewModel = ViewModelProvider(this)[AuthViewModel::class.java]

        // Find the navigation host fragment
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Set up the app bar configuration with top-level destinations
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.homeFragment,
                R.id.searchFragment,
                R.id.profileFragment,
                R.id.watchlistFragment,
                R.id.historyFragment
            )
        )

        // Set up the action bar with the nav controller
        setupActionBarWithNavController(navController, appBarConfiguration)

        // Set up bottom navigation manually (NOT using setupWithNavController
        // because the nav graph's startDestination is splashFragment which gets popped,
        // causing setupWithNavController's internal popUpTo to fail silently)
        bottomNav = findViewById(R.id.bottom_navigation)

        // Content view and bottomNav now exist, so we can take over insets.
        applyWindowInsets()
        syncSystemBarIconAppearance()

        bottomNav.setOnItemSelectedListener { item ->
            if (suppressBottomNavListener) return@setOnItemSelectedListener true

            ErrorLogManager.logEvent(TAG, "NAV", "Bottom nav item selected: ${resources.getResourceEntryName(item.itemId)}")

            // Build NavOptions that pop back to homeFragment (the real root after auth)
            // with saveState/restoreState for proper tab switching behavior
            val builder = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setRestoreState(true)

            if (item.itemId == R.id.homeFragment) {
                // Home is the root — pop everything above it to return to home
                builder.setPopUpTo(R.id.homeFragment, inclusive = false, saveState = false)
                builder.setRestoreState(false)
            } else {
                builder.setPopUpTo(R.id.homeFragment, inclusive = false, saveState = true)
            }

            return@setOnItemSelectedListener try {
                navController.navigate(item.itemId, null, builder.build())
                true
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Navigation to ${item.itemId} failed", e)
                ErrorLogManager.logEvent(TAG, "NAV_ERROR", "Navigation failed to ${resources.getResourceEntryName(item.itemId)}: ${e.message}")
                false
            }
        }

        // Prevent re-selecting the same tab from re-navigating
        bottomNav.setOnItemReselectedListener { /* no-op */ }

        // Sync bottom nav selection and visibility with current destination
        navController.addOnDestinationChangedListener { _, destination, _ ->
            ErrorLogManager.logEvent(TAG, "NAV_DESTINATION", "dest=${destination.label} (id=${destination.id})")

            when (destination.id) {
                R.id.splashFragment, R.id.loginFragment, R.id.profileSetupFragment -> {
                    bottomNav.visibility = View.GONE
                    supportActionBar?.hide()
                }
                R.id.preferencesFragment -> {
                    bottomNav.visibility = View.VISIBLE
                    supportActionBar?.hide()
                }
                else -> {
                    bottomNav.visibility = View.VISIBLE
                    supportActionBar?.show()
                }
            }

            // Keep bottom nav selection in sync with navigation
            val navItemId = when (destination.id) {
                R.id.homeFragment -> R.id.homeFragment
                R.id.searchFragment -> R.id.searchFragment
                R.id.watchlistFragment -> R.id.watchlistFragment
                R.id.historyFragment -> R.id.historyFragment
                R.id.profileFragment, R.id.preferencesFragment, R.id.malStatsFragment -> R.id.profileFragment
                else -> null // Don't change selection for detail/other screens
            }
            navItemId?.let { id ->
                if (bottomNav.selectedItemId != id) {
                    suppressBottomNavListener = true
                    bottomNav.selectedItemId = id
                    suppressBottomNavListener = false
                }
            }
        }

        // OAuth redirects are handled by the dedicated OAuthCallbackActivity
        // (see AndroidManifest); MainActivity just needs to launch the user
        // into the appropriate starting destination. AuthCallbackBus carries
        // the result of the callback so any active observer updates UI.
        ErrorLogManager.logEvent(TAG, "LIFECYCLE", "MainActivity.onCreate completed")
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    /**
     * Handle the case where the app is reopened from a deep-link tap.
     * OAuth redirects are now handled by the dedicated [com.animerec.app.ui.auth.OAuthCallbackActivity]
     * which owns the intent-filter for `animerec://auth` — this method
     * is kept for future deep-link types and simply records the intent
     * for diagnostics.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            ErrorLogManager.logEvent(TAG, "INTENT", "MainActivity onNewIntent: $uri")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ErrorLogManager.logEvent(TAG, "MEMORY", "onTrimMemory level=$level")

        // Clear Glide memory when the app is in the background
        if (level >= 15) { // ComponentCallbacks2.TRIM_MEMORY_MODERATE
            Glide.get(this).clearMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ErrorLogManager.logEvent(TAG, "MEMORY", "onLowMemory triggered")
        // Clear Glide memory when the system is low on memory
        Glide.get(this).clearMemory()
    }
}
