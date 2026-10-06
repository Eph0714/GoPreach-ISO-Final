package com.emfitsolutions.gopreach.ui.screens.pipeline

/** One-shot hand-off from the dashboard's Recently Visited: the record Publisher Assignment should open straight away. */
object PublisherAssignmentPreset {
    private var personId: String? = null

    fun set(id: String?) { personId = id }

    fun take(): String? = personId.also { personId = null }
}
