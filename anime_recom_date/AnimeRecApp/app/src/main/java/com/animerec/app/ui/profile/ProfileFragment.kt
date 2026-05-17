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
package com.animerec.app.ui.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.animerec.app.AnimeRecApp
import com.animerec.app.R
import com.animerec.app.data.Resource
import com.animerec.app.models.User
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.animerec.app.util.ErrorLogManager

/**
 * Fragment for user profile display and management.
 */
class ProfileFragment : Fragment() {
    
    private val TAG = "ProfileFragment"
    private lateinit var viewModel: ProfileViewModel
    
    // UI components — nullable to allow cleanup in onDestroyView
    private var profilePictureImageView: ImageView? = null
    private var userNameTextView: TextView? = null
    private var userDetailsTextView: TextView? = null
    private var contentTypesChipGroup: ChipGroup? = null
    private var genresChipGroup: ChipGroup? = null
    private var statsTextView: TextView? = null
    private var editProfileButton: Button? = null
    private var editPreferencesButton: Button? = null
    private var logoutButton: Button? = null
    private var sendLogsButton: Button? = null
    private var viewFullStatsButton: Button? = null
    private var viewMalProfileButton: Button? = null
    private var donateUpiButton: Button? = null
    private var developerWebsiteButton: Button? = null
    private var darkModeSwitch: MaterialSwitch? = null
    private var autoAddWatchlistSwitch: MaterialSwitch? = null
    private var watchlistStatusAutoComplete: android.widget.AutoCompleteTextView? = null
    private var watchlistStatusContainer: View? = null
    private var loadingIndicator: ProgressBar? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ProfileViewModel::class.java]
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ErrorLogManager.logEvent(TAG, "LIFECYCLE", "onViewCreated")
        
        // Initialize UI components
        profilePictureImageView = view.findViewById(R.id.profilePictureImageView)
        userNameTextView = view.findViewById(R.id.userNameTextView)
        userDetailsTextView = view.findViewById(R.id.userDetailsTextView)
        contentTypesChipGroup = view.findViewById(R.id.contentTypesChipGroup)
        genresChipGroup = view.findViewById(R.id.genresChipGroup)
        statsTextView = view.findViewById(R.id.statsTextView)
        editProfileButton = view.findViewById(R.id.editProfileButton)
        editPreferencesButton = view.findViewById(R.id.editPreferencesButton)
        logoutButton = view.findViewById(R.id.logoutButton)
        sendLogsButton = view.findViewById(R.id.sendLogsButton)
        viewFullStatsButton = view.findViewById(R.id.viewFullStatsButton)
        viewMalProfileButton = view.findViewById(R.id.viewMalProfileButton)
        donateUpiButton = view.findViewById(R.id.donateUpiButton)
        developerWebsiteButton = view.findViewById(R.id.developerWebsiteButton)
        darkModeSwitch = view.findViewById(R.id.darkModeSwitch)
        autoAddWatchlistSwitch = view.findViewById(R.id.autoAddWatchlistSwitch)
        watchlistStatusAutoComplete = view.findViewById(R.id.watchlistStatusAutoComplete)
        watchlistStatusContainer = view.findViewById(R.id.watchlistStatusContainer)
        loadingIndicator = view.findViewById(R.id.loadingIndicator)

        // Set up dark mode switch
        val themePrefs = requireContext().getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
        val currentNightMode = themePrefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        darkModeSwitch?.isChecked = currentNightMode == AppCompatDelegate.MODE_NIGHT_YES

        darkModeSwitch?.setOnCheckedChangeListener { _, isChecked ->
            val nightMode = if (isChecked) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
            ErrorLogManager.logEvent(TAG, "THEME", "Dark mode toggled: isChecked=$isChecked")
            themePrefs.edit().putInt("night_mode", nightMode).apply()
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }

        val settingsPrefs = requireContext().getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        
        // Auto-add switch
        val autoAddEnabled = settingsPrefs.getBoolean("pref_auto_add_mal", true)
        autoAddWatchlistSwitch?.isChecked = autoAddEnabled
        watchlistStatusContainer?.visibility = if (autoAddEnabled) View.VISIBLE else View.GONE
        
        autoAddWatchlistSwitch?.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("pref_auto_add_mal", isChecked).apply()
            watchlistStatusContainer?.visibility = if (isChecked) View.VISIBLE else View.GONE
            ErrorLogManager.logEvent(TAG, "SETTINGS", "Auto-add toggled: $isChecked")
        }
        
        // Watchlist status dropdown
        val statusLabels = arrayOf("Plan to Watch", "Watching", "On Hold")
        val statusValues = arrayOf("plan_to_watch", "watching", "on_hold")
        
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, statusLabels)
        watchlistStatusAutoComplete?.setAdapter(adapter)
        
        val currentStatusValue = settingsPrefs.getString("pref_watchlist_status", "plan_to_watch")
        val currentIndex = statusValues.indexOf(currentStatusValue).coerceAtLeast(0)
        watchlistStatusAutoComplete?.setText(statusLabels[currentIndex], false)
        
        watchlistStatusAutoComplete?.setOnItemClickListener { _, _, position, _ ->
            val selectedValue = statusValues[position]
            settingsPrefs.edit().putString("pref_watchlist_status", selectedValue).apply()
            ErrorLogManager.logEvent(TAG, "SETTINGS", "Watchlist status set to: $selectedValue")
        }

        // Set up button clicks
        editProfileButton?.setOnClickListener {
            findNavController().navigate(R.id.action_profileFragment_to_profileSetupFragment)
        }
        
        editPreferencesButton?.setOnClickListener {
            findNavController().navigate(R.id.action_profileFragment_to_preferencesFragment)
        }
        
        logoutButton?.setOnClickListener {
            showLogoutConfirmationDialog()
        }
        
        sendLogsButton?.setOnClickListener {
            ErrorLogManager.sendErrorLogs(requireContext())
        }
        
        viewFullStatsButton?.setOnClickListener {
            ErrorLogManager.logEvent(TAG, "NAV", "Navigating to MAL stats")
            findNavController().navigate(R.id.action_profileFragment_to_malStatsFragment)
        }

        viewMalProfileButton?.setOnClickListener {
            openMalProfile()
        }

        donateUpiButton?.setOnClickListener {
            openUpiPayment()
        }

        developerWebsiteButton?.setOnClickListener {
            openDeveloperWebsite()
        }
        
        // Observe user profile data
        viewModel.userProfile.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    showLoading(true)
                }
                is Resource.Success -> {
                    showLoading(false)
                    updateUIWithUserData(resource.data)
                    ErrorLogManager.logEvent(TAG, "DATA", "Profile loaded: ${resource.data.name}")
                }
                is Resource.Error -> {
                    showLoading(false)
                    Toast.makeText(context, resource.message, Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Error loading profile: ${resource.message}")
                    ErrorLogManager.logEvent(TAG, "ERROR", "Profile load error: ${resource.message}")
                }
            }
        }
        
        // Observe anime stats
        viewModel.animeStats.observe(viewLifecycleOwner) { resource ->
            if (resource is Resource.Success) {
                val stats = resource.data
                statsTextView?.text = buildString {
                    append("Anime Stats:\n")
                    append("• Watching: ${stats.numItemsWatching}\n")
                    append("• Completed: ${stats.numItemsCompleted}\n")
                    append("• On Hold: ${stats.numItemsOnHold}\n")
                    append("• Dropped: ${stats.numItemsDropped}\n")
                    append("• Plan to Watch: ${stats.numItemsPlanToWatch}\n")
                    append("• Total: ${stats.numItems}\n")
                    append("• Mean Score: ${String.format("%.1f", stats.meanScore)}")
                }
            }
        }
        
        // Load data
        viewModel.loadUserProfile()
    }
    
    /**
     * Update UI with user data.
     */
    private fun updateUIWithUserData(user: User) {
        // Set user name
        userNameTextView?.text = user.name
        
        // Set user details
        val detailsBuilder = StringBuilder()
        if (user.age != null) {
            detailsBuilder.append("Age: ${user.age}")
        }
        if (!user.gender.isNullOrEmpty()) {
            if (detailsBuilder.isNotEmpty()) detailsBuilder.append(" • ")
            detailsBuilder.append("Gender: ${user.gender}")
        }
        if (!user.location.isNullOrEmpty()) {
            if (detailsBuilder.isNotEmpty()) detailsBuilder.append(" • ")
            detailsBuilder.append("Location: ${user.location}")
        }
        
        userDetailsTextView?.text = detailsBuilder.toString()
        userDetailsTextView?.visibility = if (detailsBuilder.isEmpty()) View.GONE else View.VISIBLE
        
        // Load profile picture if available
        if (!user.picture.isNullOrEmpty()) {
            profilePictureImageView?.let {
                Glide.with(this)
                    .load(user.picture)
                    .circleCrop()
                    .into(it)
            }
        }
        
        // Set content type chips
        contentTypesChipGroup?.removeAllViews()
        for (contentType in user.contentPreferences) {
            val chip = Chip(requireContext()).apply {
                text = when (contentType) {
                    "anime" -> "Anime"
                    "manga" -> "Manga"
                    "novels" -> "Light Novels"
                    else -> contentType.replaceFirstChar { it.uppercase() }
                }
                isCheckable = false
            }
            contentTypesChipGroup?.addView(chip)
        }
        
        // Set genre chips
        genresChipGroup?.removeAllViews()
        for (genre in user.genrePreferences) {
            val chip = Chip(requireContext()).apply {
                text = genre
                isCheckable = false
            }
            genresChipGroup?.addView(chip)
        }
        
        // Load anime stats
        viewModel.loadAnimeStats()
    }
    
    /**
     * Show loading indicator.
     */
    private fun showLoading(isLoading: Boolean) {
        loadingIndicator?.visibility = if (isLoading) View.VISIBLE else View.GONE
        profilePictureImageView?.visibility = if (isLoading) View.GONE else View.VISIBLE
        userNameTextView?.visibility = if (isLoading) View.GONE else View.VISIBLE
        userDetailsTextView?.visibility = if (isLoading) View.GONE else View.VISIBLE
        contentTypesChipGroup?.visibility = if (isLoading) View.GONE else View.VISIBLE
        genresChipGroup?.visibility = if (isLoading) View.GONE else View.VISIBLE
        statsTextView?.visibility = if (isLoading) View.GONE else View.VISIBLE
        editProfileButton?.isEnabled = !isLoading
        editPreferencesButton?.isEnabled = !isLoading
        logoutButton?.isEnabled = !isLoading
    }
    
    /**
     * Show logout confirmation dialog.
     */
    private fun showLogoutConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout? You will need to login again.")
            .setPositiveButton("Logout") { _, _ ->
                logout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Open the user's MAL profile in a browser.
     */
    private fun openMalProfile() {
        val currentProfile = viewModel.userProfile.value
        if (currentProfile is Resource.Success) {
            val username = currentProfile.data.name
            val url = "https://myanimelist.net/profile/$username"
            ErrorLogManager.logEvent(TAG, "NAV", "Opening MAL profile: $url")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
                ErrorLogManager.logEvent(TAG, "ERROR", "Failed to open MAL profile: ${e.message}")
            }
        } else {
            Toast.makeText(context, "Profile not loaded yet", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Open UPI payment app for donation.
     */
    private fun openUpiPayment() {
        ErrorLogManager.logEvent(TAG, "ACTION", "Opening UPI donation")
        val upiUri = Uri.parse(
            "upi://pay?pa=technicallittlemaster-1@okaxis" +
            "&pn=Shuvam%20Banerji%20Seal" +
            "&cu=INR"
        )
        val intent = Intent(Intent.ACTION_VIEW, upiUri)
        try {
            startActivity(Intent.createChooser(intent, "Donate via UPI"))
        } catch (e: Exception) {
            Toast.makeText(context, "No UPI app found on this device", Toast.LENGTH_LONG).show()
            ErrorLogManager.logEvent(TAG, "ERROR", "No UPI app found: ${e.message}")
        }
    }

    /**
     * Open the developer's website in a browser.
     */
    private fun openDeveloperWebsite() {
        ErrorLogManager.logEvent(TAG, "NAV", "Opening developer website")
        val url = "https://shuvam-banerji-seal.github.io"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
            ErrorLogManager.logEvent(TAG, "ERROR", "Failed to open developer website: ${e.message}")
        }
    }

    /**
     * Logout the user.
     */
    private fun logout() {
        ErrorLogManager.logEvent(TAG, "AUTH", "User logged out")
        val authManager = (requireActivity().application as AnimeRecApp).authManager
        authManager.logout()
        
        // Navigate to login screen
        findNavController().navigate(R.id.action_profileFragment_to_loginFragment)
    }

    override fun onDestroyView() {
        profilePictureImageView?.let { Glide.with(this).clear(it) }
        super.onDestroyView()
        ErrorLogManager.logEvent(TAG, "LIFECYCLE", "onDestroyView — nulling 16 view refs")
        profilePictureImageView = null
        userNameTextView = null
        userDetailsTextView = null
        contentTypesChipGroup = null
        genresChipGroup = null
        statsTextView = null
        editProfileButton = null
        editPreferencesButton = null
        logoutButton = null
        sendLogsButton = null
        viewFullStatsButton = null
        viewMalProfileButton = null
        donateUpiButton = null
        developerWebsiteButton = null
        darkModeSwitch = null
        autoAddWatchlistSwitch = null
        watchlistStatusAutoComplete = null
        watchlistStatusContainer = null
        loadingIndicator = null
    }
}