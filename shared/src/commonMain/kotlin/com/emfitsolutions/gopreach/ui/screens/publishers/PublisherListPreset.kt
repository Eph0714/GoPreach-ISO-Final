package com.emfitsolutions.gopreach.ui.screens.publishers

import com.emfitsolutions.gopreach.data.model.PublisherCategory

/** One-shot hand-off from the dashboard's Quick Access cards: the category the Publishers list should open filtered to. */
object PublisherListPreset {
    private var category: PublisherCategory? = null

    fun set(value: PublisherCategory?) { category = value }

    /** Returns the pending category once and clears it, so a later normal visit opens unfiltered. */
    fun take(): PublisherCategory? = category.also { category = null }
}
