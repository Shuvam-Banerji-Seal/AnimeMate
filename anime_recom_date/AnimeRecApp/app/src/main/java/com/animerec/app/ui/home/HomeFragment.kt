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
package com.animerec.app.ui.home

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.animerec.app.R
import com.animerec.app.data.Resource
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yuyakaido.android.cardstackview.CardStackLayoutManager
import com.yuyakaido.android.cardstackview.CardStackListener
import com.yuyakaido.android.cardstackview.CardStackView
import com.yuyakaido.android.cardstackview.Direction
import com.yuyakaido.android.cardstackview.Duration
import com.yuyakaido.android.cardstackview.StackFrom
import com.yuyakaido.android.cardstackview.SwipeAnimationSetting

/**
 * Home fragment with the swipe recommendation interface.
 */
class HomeFragment : Fragment(), CardStackListener {
    
    private lateinit var viewModel: RecommendationViewModel
    private var cardStackView: CardStackView? = null
    private var layoutManager: CardStackLayoutManager? = null
    private var adapter: AnimeCardAdapter? = null
    private var loadingIndicator: ProgressBar? = null
    private var emptyStateText: TextView? = null
    private var mediaFilterChipGroup: ChipGroup? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[RecommendationViewModel::class.java]
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize UI components
        cardStackView = view.findViewById(R.id.card_stack_view)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        emptyStateText = view.findViewById(R.id.empty_state_text)
        mediaFilterChipGroup = view.findViewById(R.id.media_filter_chip_group)
        
        // Set up media format filter chips
        mediaFilterChipGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when {
                checkedIds.contains(R.id.chip_anime) -> "anime"
                checkedIds.contains(R.id.chip_manga) -> "manga"
                checkedIds.contains(R.id.chip_novel) -> "novel"
                else -> null // "All" or no selection
            }
            viewModel.setMediaFilter(filter)
        }
        
        // Enable hardware acceleration for smoother animations
        cardStackView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        
        // Set up the card stack view
        layoutManager = CardStackLayoutManager(requireContext(), this).apply {
            setStackFrom(StackFrom.Top)
            setVisibleCount(3)
            setTranslationInterval(8.0f)
            setScaleInterval(0.95f)
            setMaxDegree(20.0f)
            setDirections(Direction.HORIZONTAL + Direction.VERTICAL)
            setCanScrollHorizontal(true)
            setCanScrollVertical(true)
            setSwipeThreshold(0.3f)
        }
        
        adapter = AnimeCardAdapter(requireContext())
        
        cardStackView?.layoutManager = layoutManager
        cardStackView?.adapter = adapter
        
        // Observe recommendations
        viewModel.recommendations.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    showLoading(true)
                    emptyStateText?.visibility = View.GONE
                }
                is Resource.Success -> {
                    showLoading(false)
                    
                    if (resource.data.isEmpty()) {
                        emptyStateText?.visibility = View.VISIBLE
                        cardStackView?.visibility = View.GONE
                    } else {
                        emptyStateText?.visibility = View.GONE
                        cardStackView?.visibility = View.VISIBLE
                        adapter?.submitList(resource.data)
                        
                        // Check if we should show the tutorial
                        showSwipeTutorialIfNeeded()
                    }
                }
                is Resource.Error -> {
                    showLoading(false)
                    Toast.makeText(requireContext(), resource.message, Toast.LENGTH_LONG).show()
                    
                    // If we have no recommendations, show empty state
                    if ((adapter?.itemCount ?: 0) == 0) {
                        emptyStateText?.text = resource.message
                        emptyStateText?.visibility = View.VISIBLE
                        cardStackView?.visibility = View.GONE
                    }
                }
            }
        }
        
        // Set up swipe instruction clicks to perform the swipe actions
        view.findViewById<TextView>(R.id.swipe_left_instruction).setOnClickListener {
            if ((adapter?.itemCount ?: 0) > 0) {
                performSwipe(Direction.Left)
            }
        }
        
        view.findViewById<TextView>(R.id.swipe_right_instruction).setOnClickListener {
            if ((adapter?.itemCount ?: 0) > 0) {
                performSwipe(Direction.Right)
            }
        }
        
        view.findViewById<TextView>(R.id.swipe_up_instruction).setOnClickListener {
            if ((adapter?.itemCount ?: 0) > 0) {
                performSwipe(Direction.Top)
            }
        }
        
        view.findViewById<TextView>(R.id.swipe_down_instruction).setOnClickListener {
            if ((adapter?.itemCount ?: 0) > 0) {
                performSwipe(Direction.Bottom)
            }
        }
        
        // Load initial recommendations
        viewModel.loadRecommendations()
    }
    
    /**
     * Show swipe tutorial dialog for first-time users.
     */
    private fun showSwipeTutorialIfNeeded() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val tutorialShown = prefs.getBoolean("swipe_tutorial_shown", false)
        
        if (!tutorialShown) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("How to Use")
                .setMessage(
                    "• Swipe RIGHT to add to your watchlist\n" +
                    "• Swipe LEFT to mark as not interested\n" +
                    "• Swipe UP to mark as already watched/read\n" +
                    "• Swipe DOWN to view more details"
                )
                .setPositiveButton("Got it!") { _, _ ->
                    prefs.edit().putBoolean("swipe_tutorial_shown", true).apply()
                }
                .setCancelable(false)
                .show()
        }
    }
    
    /**
     * Perform a swipe in the given direction.
     */
    private fun performSwipe(direction: Direction) {
        val setting = SwipeAnimationSetting.Builder()
            .setDirection(direction)
            .setDuration(Duration.Slow.duration) // Changed from Normal to Slow for better visibility
            .build()
        
        layoutManager?.setSwipeAnimationSetting(setting)
        cardStackView?.swipe()
    }
    
    /**
     * Show or hide the loading indicator.
     */
    private fun showLoading(isLoading: Boolean) {
        loadingIndicator?.visibility = if (isLoading) View.VISIBLE else View.GONE
    }
    
    // CardStackListener implementation
    override fun onCardDragging(direction: Direction?, ratio: Float) {
        // Update UI based on drag direction if needed
    }
    
    override fun onCardSwiped(direction: Direction?) {
        val currentAdapter = adapter ?: return
        val currentLayoutManager = layoutManager ?: return
        if (currentAdapter.itemCount == 0) return
        
        val swipedPosition = currentLayoutManager.topPosition - 1
        if (swipedPosition < 0 || swipedPosition >= currentAdapter.itemCount) return
        
        val swipedItem = currentAdapter.currentList[swipedPosition]
        
        when (direction) {
            Direction.Right -> {
                // Check user preferences for auto-adding to MAL
                val settingsPrefs = requireContext().getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                if (settingsPrefs.getBoolean("pref_auto_add_mal", true)) {
                    val status = settingsPrefs.getString("pref_watchlist_status", "plan_to_watch") ?: "plan_to_watch"
                    viewModel.addToWatchlist(swipedItem, status)
                    Toast.makeText(requireContext(), "Added to watchlist", Toast.LENGTH_SHORT).show()
                }
            }
            Direction.Left -> {
                // Mark as not interested
                viewModel.markAsNotInterested(swipedItem)
                Toast.makeText(requireContext(), "Marked as not interested", Toast.LENGTH_SHORT).show()
            }
            Direction.Top -> {
                // Mark as already watched
                viewModel.markAsWatched(swipedItem)
                Toast.makeText(requireContext(), "Marked as watched", Toast.LENGTH_SHORT).show()
            }
            Direction.Bottom -> {
                // Show details
                viewModel.showDetails(swipedItem)
                
                // Navigate to details fragment (safe — check current destination first)
                try {
                    val navController = findNavController()
                    if (navController.currentDestination?.id == R.id.homeFragment) {
                        val bundle = Bundle().apply {
                            putInt("contentId", swipedItem.id)
                            putString("contentType", swipedItem.type.name)
                        }
                        navController.navigate(R.id.action_homeFragment_to_detailsFragment, bundle)
                    }
                } catch (e: IllegalArgumentException) {
                    Log.w("HomeFragment", "Navigation to details failed: ${e.message}")
                }
            }
            else -> {}
        }
        
        // Load more if needed
        if (currentLayoutManager.topPosition >= currentAdapter.itemCount - 3) {
            viewModel.loadMoreRecommendations()
        }
    }
    
    override fun onCardRewound() {
        // Handle card rewound (undo) if needed
    }
    
    override fun onCardCanceled() {
        // Handle card cancellation if needed
    }
    
    override fun onCardAppeared(view: View?, position: Int) {
        // Handle card appearance if needed
    }
    
    override fun onCardDisappeared(view: View?, position: Int) {
        // Handle card disappearance if needed
    }
    
    override fun onDestroyView() {
        // Null out all view references to prevent 34+ MB memory leak.
        // CardStackView cannot accept null layout manager / adapter at the
        // library level, so we just drop our strong references instead.
        cardStackView = null
        layoutManager = null
        adapter = null
        loadingIndicator = null
        emptyStateText = null
        mediaFilterChipGroup = null
        super.onDestroyView()
    }
}