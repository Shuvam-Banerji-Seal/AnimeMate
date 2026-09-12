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
package com.animerec.app.ui.details

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
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.navigation.fragment.findNavController
import com.animerec.app.R
import com.animerec.app.data.Resource
import com.animerec.app.models.AnimeContent
import com.animerec.app.models.ContentType
import com.animerec.app.util.ErrorLogManager
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Fragment for displaying content details.
 */
class DetailsFragment : Fragment() {
    
    private val TAG = "DetailsFragment"
    private lateinit var viewModel: DetailsViewModel
    
    // UI components — nullable to allow cleanup in onDestroyView
    private var coverImageView: ImageView? = null
    private var titleTextView: TextView? = null
    private var typeAndStatusTextView: TextView? = null
    private var ratingTextView: TextView? = null
    private var rankTextView: TextView? = null
    private var creditsTextView: TextView? = null
    private var synopsisTextView: TextView? = null
    private var genresChipGroup: ChipGroup? = null
    private var watchStatusButton: Button? = null
    private var watchTrailerButton: Button? = null
    private var viewOnMalButton: Button? = null
    private var userRatingBar: RatingBar? = null
    private var similarContentRecyclerView: RecyclerView? = null
    private var loadingIndicator: ProgressBar? = null
    private var errorTextView: TextView? = null
    
    private var similarContentAdapter: SimilarContentAdapter? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[DetailsViewModel::class.java]
        
        // Get content ID and type from arguments
        arguments?.getInt("contentId")?.let { contentId ->
            val contentTypeStr = arguments?.getString("contentType") ?: ContentType.ANIME.name
            val contentType = ContentType.valueOf(contentTypeStr)
            viewModel.loadContentDetails(contentId, contentType)
            viewModel.loadSimilarContent(contentId, contentType)
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_details, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ErrorLogManager.logEvent(TAG, "LIFECYCLE", "onViewCreated")
        
        // Initialize UI components
        coverImageView = view.findViewById(R.id.coverImageView)
        titleTextView = view.findViewById(R.id.titleTextView)
        typeAndStatusTextView = view.findViewById(R.id.typeAndStatusTextView)
        ratingTextView = view.findViewById(R.id.ratingTextView)
        rankTextView = view.findViewById(R.id.rankTextView)
        creditsTextView = view.findViewById(R.id.creditsTextView)
        synopsisTextView = view.findViewById(R.id.synopsisTextView)
        genresChipGroup = view.findViewById(R.id.genresChipGroup)
        watchStatusButton = view.findViewById(R.id.watchStatusButton)
        watchTrailerButton = view.findViewById(R.id.watchTrailerButton)
        viewOnMalButton = view.findViewById(R.id.viewOnMalButton)
        userRatingBar = view.findViewById(R.id.userRatingBar)
        similarContentRecyclerView = view.findViewById(R.id.similarContentRecyclerView)
        loadingIndicator = view.findViewById(R.id.loadingIndicator)
        errorTextView = view.findViewById(R.id.errorTextView)
        
        // Set up similar content recycler view
        similarContentAdapter = SimilarContentAdapter(requireContext()) { similarContent ->
            val bundle = Bundle().apply {
                putInt("contentId", similarContent.id)
                putString("contentType", similarContent.type.name)
            }
            
            // Navigate using the Navigation Component
            findNavController().navigate(R.id.detailsFragment, bundle)
        }
        
        similarContentRecyclerView?.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = similarContentAdapter
        }
        
        // Observe content details
        viewModel.contentDetails.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    showLoading(true)
                    errorTextView?.visibility = View.GONE
                }
                is Resource.Success -> {
                    showLoading(false)
                    errorTextView?.visibility = View.GONE
                    updateUIWithContentDetails(resource.data)
                    ErrorLogManager.logEvent(TAG, "DATA", "Details loaded: ${resource.data.title}")
                }
                is Resource.Error -> {
                    showLoading(false)
                    errorTextView?.visibility = View.VISIBLE
                    errorTextView?.text = resource.message
                    Log.e(TAG, "Error loading content details: ${resource.message}")
                    ErrorLogManager.logEvent(TAG, "ERROR", "Details load error: ${resource.message}")
                }
            }
        }
        
        // Observe similar content
        viewModel.similarContent.observe(viewLifecycleOwner) { resource ->
            if (resource is Resource.Success && resource.data.isNotEmpty()) {
                similarContentAdapter?.submitList(resource.data)
                similarContentRecyclerView?.visibility = View.VISIBLE
            } else {
                similarContentRecyclerView?.visibility = View.GONE
            }
        }
        
        // Set up user rating bar listener
        userRatingBar?.setOnRatingBarChangeListener { _, rating, fromUser ->
            if (fromUser) {
                val currentValue = viewModel.contentDetails.value
                if (currentValue is Resource.Success) {
                    val content = currentValue.data
                    val score = (rating * 2).coerceIn(1f, 10f).toInt() // Convert 0-5 to 1-10
                    viewModel.rateContent(content, score)
                    Toast.makeText(context, "Rated ${content.title} $score/10", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun updateUIWithContentDetails(content: AnimeContent) {
        // Set title
        titleTextView?.text = content.title
        
        // Set type and status
        val typeText = when (content.type) {
            ContentType.ANIME -> "Anime"
            ContentType.MANGA -> "Manga"
            ContentType.NOVEL -> "Light Novel"
        }
        typeAndStatusTextView?.text = "$typeText • ${formatStatus(content.status)}"
        
        // Set rating
        if (content.malScore > 0) {
            ratingTextView?.text = "★ ${String.format("%.1f", content.malScore)}"
            ratingTextView?.visibility = View.VISIBLE
        } else {
            ratingTextView?.visibility = View.GONE
        }
        
        // MAL rankings
        val ranks = listOfNotNull(
            content.rank?.let { "Ranked #$it" },
            content.popularity?.let { "Popularity #$it" }
        )
        rankTextView?.text = ranks.joinToString("  ·  ")
        rankTextView?.visibility = if (ranks.isEmpty()) View.GONE else View.VISIBLE

        // Studio / author, source medium, episode length, community size.
        // Every one of these already came back on the API response and was
        // previously thrown away by the mapping layer.
        val credits = buildList {
            (content.studios + content.authors).takeIf { it.isNotEmpty() }
                ?.let { add(it.joinToString(", ")) }
            content.source.takeIf { it.isNotBlank() }
                ?.let { add("Source: ${it.replace('_', ' ')}") }
            content.averageEpisodeDurationSeconds?.takeIf { it > 0 }
                ?.let { add("${it / 60} min/ep") }
            content.numListUsers.takeIf { it > 0 }
                ?.let { add("${formatCompact(it)} members") }
        }
        creditsTextView?.text = credits.joinToString("  ·  ")
        creditsTextView?.visibility = if (credits.isEmpty()) View.GONE else View.VISIBLE

        // Set synopsis
        synopsisTextView?.text = content.synopsis
        
        // Set genres
        genresChipGroup?.removeAllViews()
        for (genre in content.genres) {
            val chip = Chip(requireContext()).apply {
                text = genre
                isCheckable = false
            }
            genresChipGroup?.addView(chip)
        }
        
        // Set user rating
        userRatingBar?.rating = content.userScore?.toFloat()?.div(2) ?: 0f
        
        // Load cover image
        if (content.imageUrl.isNotEmpty()) {
            coverImageView?.let {
                Glide.with(this)
                    .load(content.imageUrl)
                    .centerCrop()
                    .into(it)
            }
        }
        
        // Set up watch status button
        watchStatusButton?.text = when {
            content.isCompleted -> "Completed"
            content.inWatchlist -> if (content.type == ContentType.ANIME) "In Plan to Watch" else "In Plan to Read"
            else -> if (content.type == ContentType.ANIME) "Add to Plan to Watch" else "Add to Plan to Read"
        }
        
        watchStatusButton?.setOnClickListener {
            val newStatus = when {
                content.isCompleted -> if (content.type == ContentType.ANIME) "watching" else "reading"
                content.inWatchlist -> "completed"
                else -> if (content.type == ContentType.ANIME) "plan_to_watch" else "plan_to_read"
            }
            viewModel.updateStatus(content, newStatus)
        }
        
        // Set up watch trailer button
        if (content.type == ContentType.ANIME && !content.trailerUrl.isNullOrEmpty()) {
            watchTrailerButton?.visibility = View.VISIBLE
            watchTrailerButton?.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(content.trailerUrl))
                startActivity(intent)
            }
        } else {
            watchTrailerButton?.visibility = View.GONE
        }

        // Set up "View on MAL" button
        val malPath = when (content.type) {
            ContentType.ANIME -> "anime"
            ContentType.MANGA -> "manga"
            ContentType.NOVEL -> "manga" // Novels are listed under manga on MAL
        }
        val malUrl = "https://myanimelist.net/$malPath/${content.id}"
        viewOnMalButton?.setOnClickListener {
            ErrorLogManager.logEvent(TAG, "NAV", "Opening MAL page: $malUrl")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(malUrl)))
            } catch (e: Exception) {
                Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /** 3_200_000 -> "3.2M", 45_000 -> "45K". */
    private fun formatCompact(value: Int): String = when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000 -> "${value / 1_000}K"
        else -> value.toString()
    }

    private fun formatStatus(status: String): String {
        return when (status) {
            "currently_airing" -> "Currently Airing"
            "finished_airing" -> "Finished Airing"
            "not_yet_aired" -> "Not Yet Aired"
            "currently_publishing" -> "Currently Publishing"
            "finished" -> "Finished"
            "plan_to_watch" -> "Plan to Watch"
            "plan_to_read" -> "Plan to Read"
            "watching" -> "Watching"
            "reading" -> "Reading"
            "completed" -> "Completed"
            "on_hold" -> "On Hold"
            "dropped" -> "Dropped"
            else -> status.replaceFirstChar { it.uppercase() }
        }
    }
    
    private fun showLoading(isLoading: Boolean) {
        loadingIndicator?.visibility = if (isLoading) View.VISIBLE else View.GONE
        coverImageView?.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        titleTextView?.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        typeAndStatusTextView?.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        ratingTextView?.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        synopsisTextView?.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        genresChipGroup?.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        watchStatusButton?.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        watchTrailerButton?.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
        userRatingBar?.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
    }

    override fun onDestroyView() {
        coverImageView?.let { Glide.with(this).clear(it) }
        super.onDestroyView()
        ErrorLogManager.logEvent(TAG, "LIFECYCLE", "onDestroyView \u2014 nulling 13 view refs")
        coverImageView = null
        titleTextView = null
        typeAndStatusTextView = null
        ratingTextView = null
        rankTextView = null
        creditsTextView = null
        synopsisTextView = null
        genresChipGroup = null
        watchStatusButton = null
        watchTrailerButton = null
        viewOnMalButton = null
        userRatingBar = null
        similarContentRecyclerView?.adapter = null
        similarContentRecyclerView = null
        loadingIndicator = null
        errorTextView = null
        similarContentAdapter = null
    }
}