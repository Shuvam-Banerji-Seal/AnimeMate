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
package com.animerec.app.ui.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animerec.app.R
import com.animerec.app.data.Resource
import com.animerec.app.models.AnimeContent
import com.animerec.app.util.ErrorLogManager
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText

/**
 * Search screen — look up any anime, manga or light novel by title.
 */
class SearchFragment : Fragment(), SearchAdapter.OnResultActionListener {

    private val TAG = "SearchFragment"

    private lateinit var viewModel: SearchViewModel

    private var searchEditText: TextInputEditText? = null
    private var scopeChipGroup: ChipGroup? = null
    private var resultsRecyclerView: RecyclerView? = null
    private var loadingIndicator: ProgressBar? = null
    private var statusTextView: TextView? = null
    private var adapter: SearchAdapter? = null
    private var queryWatcher: TextWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[SearchViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        searchEditText = view.findViewById(R.id.searchEditText)
        scopeChipGroup = view.findViewById(R.id.scopeChipGroup)
        resultsRecyclerView = view.findViewById(R.id.resultsRecyclerView)
        loadingIndicator = view.findViewById(R.id.loadingIndicator)
        statusTextView = view.findViewById(R.id.statusTextView)

        adapter = SearchAdapter(requireContext(), this)
        resultsRecyclerView?.layoutManager = LinearLayoutManager(requireContext())
        resultsRecyclerView?.adapter = adapter

        queryWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.onQueryChanged(s?.toString().orEmpty())
            }
        }.also { searchEditText?.addTextChangedListener(it) }

        searchEditText?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                viewModel.retry()
                true
            } else {
                false
            }
        }

        scopeChipGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val scope = when {
                checkedIds.contains(R.id.chip_scope_anime) -> SearchViewModel.Scope.ANIME
                checkedIds.contains(R.id.chip_scope_manga) -> SearchViewModel.Scope.MANGA
                checkedIds.contains(R.id.chip_scope_novels) -> SearchViewModel.Scope.NOVELS
                else -> SearchViewModel.Scope.ALL
            }
            viewModel.setScope(scope)
        }

        viewModel.results.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    loadingIndicator?.visibility = View.VISIBLE
                    statusTextView?.visibility = View.GONE
                }
                is Resource.Success -> {
                    loadingIndicator?.visibility = View.GONE
                    adapter?.submitList(resource.data) {
                        // Scroll back to the top once the new results are laid
                        // out, so a refined query doesn't leave the user
                        // halfway down a list they've never seen.
                        resultsRecyclerView?.scrollToPosition(0)
                    }
                    showStatus(
                        when {
                            viewModel.idle.value == true -> getString(R.string.search_prompt)
                            resource.data.isEmpty() -> getString(R.string.search_no_results)
                            else -> null
                        }
                    )
                }
                is Resource.Error -> {
                    loadingIndicator?.visibility = View.GONE
                    showStatus(resource.message)
                }
            }
        }

        viewModel.message.observe(viewLifecycleOwner) { message ->
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Show the placeholder text, or hide it entirely when [text] is null. */
    private fun showStatus(text: String?) {
        statusTextView?.text = text.orEmpty()
        statusTextView?.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService<InputMethodManager>()
        imm?.hideSoftInputFromWindow(searchEditText?.windowToken, 0)
    }

    override fun onResultClick(content: AnimeContent) {
        hideKeyboard()
        try {
            val navController = findNavController()
            if (navController.currentDestination?.id == R.id.searchFragment) {
                val bundle = Bundle().apply {
                    putInt("contentId", content.id)
                    putString("contentType", content.type.name)
                }
                navController.navigate(R.id.action_searchFragment_to_detailsFragment, bundle)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Navigation to details failed: ${e.message}")
            ErrorLogManager.logEvent(TAG, "NAV_ERROR", "Search → details failed: ${e.message}")
        }
    }

    override fun onAddToList(content: AnimeContent) {
        viewModel.addToList(content)
    }

    override fun onDestroyView() {
        // Detach the watcher before dropping the view, otherwise it keeps
        // firing against a ViewModel whose observers are already gone.
        queryWatcher?.let { searchEditText?.removeTextChangedListener(it) }
        queryWatcher = null
        resultsRecyclerView?.adapter = null
        searchEditText = null
        scopeChipGroup = null
        resultsRecyclerView = null
        loadingIndicator = null
        statusTextView = null
        adapter = null
        super.onDestroyView()
    }
}
