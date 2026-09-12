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

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.animerec.app.R
import com.animerec.app.models.AnimeContent
import com.animerec.app.models.ContentType
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton

/**
 * Adapter for search results.
 */
class SearchAdapter(
    private val context: Context,
    private val listener: OnResultActionListener
) : ListAdapter<AnimeContent, SearchAdapter.ResultViewHolder>(DiffCallback()) {

    interface OnResultActionListener {
        fun onResultClick(content: AnimeContent)
        fun onAddToList(content: AnimeContent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return ResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverImageView: ImageView = itemView.findViewById(R.id.coverImageView)
        private val titleTextView: TextView = itemView.findViewById(R.id.titleTextView)
        private val typeTextView: TextView = itemView.findViewById(R.id.typeTextView)
        private val metaTextView: TextView = itemView.findViewById(R.id.metaTextView)
        private val scoreTextView: TextView = itemView.findViewById(R.id.scoreTextView)
        private val addButton: MaterialButton = itemView.findViewById(R.id.addButton)

        fun bind(content: AnimeContent) {
            titleTextView.text = content.title
            typeTextView.text = content.displayType()

            // Year · length, skipping whichever pieces this item doesn't have
            // rather than rendering "null" or a stray separator.
            val meta = listOfNotNull(
                content.releaseYear?.toString(),
                content.lengthLabel()
            )
            metaTextView.text = meta.joinToString(" · ")
            metaTextView.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE

            if (content.malScore > 0) {
                scoreTextView.text = String.format("★ %.1f", content.malScore)
                scoreTextView.visibility = View.VISIBLE
            } else {
                scoreTextView.visibility = View.GONE
            }

            if (content.imageUrl.isNotEmpty()) {
                Glide.with(context)
                    .load(content.imageUrl)
                    .centerCrop()
                    .placeholder(R.drawable.gradient_overlay)
                    .into(coverImageView)
            } else {
                coverImageView.setImageResource(R.drawable.gradient_overlay)
            }

            addButton.setOnClickListener { listener.onAddToList(content) }
            itemView.setOnClickListener { listener.onResultClick(content) }
        }

        /**
         * MAL returns light novels through the manga endpoint, so they arrive
         * typed MANGA. Fall back to the media type to label them correctly.
         */
        private fun AnimeContent.displayType(): String = when (type) {
            ContentType.ANIME -> mediaType.replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
                .ifEmpty { "Anime" }
            ContentType.NOVEL -> "Light Novel"
            ContentType.MANGA -> when (mediaType) {
                "light_novel", "novel" -> "Light Novel"
                "manhwa" -> "Manhwa"
                "manhua" -> "Manhua"
                "one_shot" -> "One-shot"
                else -> "Manga"
            }
        }

        private fun AnimeContent.lengthLabel(): String? = when (type) {
            ContentType.ANIME -> episodes?.takeIf { it > 0 }?.let { "$it eps" }
            ContentType.MANGA, ContentType.NOVEL ->
                chapters?.takeIf { it > 0 }?.let { "$it ch" }
                    ?: volumes?.takeIf { it > 0 }?.let { "$it vol" }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AnimeContent>() {
        // contentKey, not id: anime and manga IDs overlap, so keying on id
        // alone makes DiffUtil treat unrelated items as the same row.
        override fun areItemsTheSame(oldItem: AnimeContent, newItem: AnimeContent): Boolean =
            oldItem.contentKey == newItem.contentKey

        override fun areContentsTheSame(oldItem: AnimeContent, newItem: AnimeContent): Boolean =
            oldItem == newItem
    }

    override fun onViewRecycled(holder: ResultViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(context).clear(holder.itemView.findViewById<ImageView>(R.id.coverImageView))
    }
}
