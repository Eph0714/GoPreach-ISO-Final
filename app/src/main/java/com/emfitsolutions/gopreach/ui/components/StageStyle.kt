package com.emfitsolutions.gopreach.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.emfitsolutions.gopreach.data.model.PipelineStage

/**
 * The colour code of a record's stage, the same wherever a record is listed: Bible Study blue and bold, Return Visit green, Searching plain black.
 * [Color.Unspecified] for Searching keeps the theme's own text colour (black on a light screen, white on a dark one).
 */
fun PipelineStage.stageColor(): Color = when (this) {
    PipelineStage.BIBLE_STUDY -> Color(0xFF1565C0)
    PipelineStage.RETURN_VISIT -> Color(0xFF2E7D32)
    PipelineStage.SEARCHING -> Color.Unspecified
}

fun PipelineStage.stageWeight(): FontWeight = if (this == PipelineStage.BIBLE_STUDY) FontWeight.Bold else FontWeight.SemiBold