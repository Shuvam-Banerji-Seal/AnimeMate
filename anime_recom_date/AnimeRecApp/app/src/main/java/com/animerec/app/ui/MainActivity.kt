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
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
    private lateinit var binding: com.animerec.app.databinding.ActivityMainBinding

    /** Suppresses bottom nav listener from triggering navigation during programmatic selection */
    private var suppressBottomNavListener = false

    private fun syncSystemBarColors() {
        val typedValue = android.util.TypedValue()
        
        // Status bar
        theme.resolveAttribute(android.R.attr.statusBarColor, typedValue, true)
        window.statusBarColor = typedValue.data
        
        // Nav bar
        theme.resolveAttribute(android.R.attr.navigationBarColor, typedValue, true)
        window.navigationBarColor = typedValue.data
        
        // Icons (light/dark)
        val themePrefs = getSharedPreferences("theme_prefs", android.content.Context.MODE_PRIVATE)
        val savedNightMode = themePrefs.getInt("night_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val isNightMode = if (savedNightMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            savedNightMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode
            isAppearanceLightNavigationBars = !isNightMode
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Sync system bars with applied theme 
        syncSystemBarColors()

        super.onCreate(savedInstanceState)
        binding = com.animerec.app.databinding.ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBar.updatePadding(top = systemBars.top)
            //binding.bottomNavigation.updatePadding(bottom = systemBars.bottom)
            insets
        }

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

        // Check intent for OAuth redirect if activity is started with an intent
        handleIntent(intent)
        ErrorLogManager.logEvent(TAG, "LIFECYCLE", "MainActivity.onCreate completed")
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    /**
     * Handle the case where the app is opened with a deep link
     * (for OAuth redirect handling in LoginFragment)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            ErrorLogManager.logEvent(TAG, "INTENT", "Handling intent with URI: $uri")
            if (uri.scheme == "animerec" && uri.host == "auth") {
                // Find the current fragment
                val navHostFragment = supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                val currentFragment = navHostFragment.childFragmentManager.fragments.firstOrNull()

                // Handle auth redirect in login fragment if it's current
                if (currentFragment is LoginFragment) {
                    currentFragment.handleIntent(intent)
                } else {
                    // Navigate to login fragment if needed
                    navController.navigate(R.id.loginFragment)
                }
            }
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
