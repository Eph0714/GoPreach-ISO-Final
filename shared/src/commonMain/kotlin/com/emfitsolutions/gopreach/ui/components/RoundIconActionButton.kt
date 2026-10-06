package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Small round "quick action" button — a colorless circular outline (no
 * container fill, spec request: "the button doesnt have color but the icon
 * has") with a tinted icon inside, and its label (plus an optional live
 * [value] shown right next to that label, not inside the circle — spec
 * request: "Show the Numbers... next to the text of the icon") placed below
 * the circle rather than alongside it. Used by [com.emfitsolutions.gopreach
 * .ui.screens.home.PublisherHomeScreen]'s "My Bible Study" / "My Return
 * Visit" / "Preaching Hours" actions.
 */
@Composable
fun RoundIconActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    /** e.g. "12" or "3.5" — appended after [label] in the same line, bolded
     * to stand out from the label text. `null` (the default) renders just
     * the label, as before — used by callers with nothing to count (e.g.
     * [com.emfitsolutions.gopreach.ui.screens.findlocation.FindLocationScreen]'s
     * travel-mode buttons). */
    value: String? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Color.Transparent),
            border = BorderStroke(1.5.dp, iconTint.copy(alpha = 0.4f)),
        ) {
            Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(26.dp))
        }
        Text(
            text = if (value == null) {
                buildAnnotatedString { append(label) }
            } else {
                buildAnnotatedString {
                    append("$label ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = iconTint)) { append(value) }
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
