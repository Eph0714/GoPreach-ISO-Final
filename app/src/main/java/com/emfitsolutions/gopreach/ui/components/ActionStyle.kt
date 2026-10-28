package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The Universal Action Design System: one colour and one icon per kind of action, used by every module.
 * Add/Save green, Edit/Refresh blue, Delete red, View slate, Print teal, Map purple, Export cyan, Import indigo, Cancel grey.
 * Change a value here and the whole app follows; the website mirrors these values in resources/css/app.css (--act-*).
 */
enum class ActionKind(val color: Color, val icon: ImageVector, val label: String) {
    Add(Color(0xFF16A34A), Icons.Rounded.Add, "Add"),
    Save(Color(0xFF16A34A), Icons.Rounded.Check, "Save"),
    Edit(Color(0xFF2563EB), Icons.Rounded.Edit, "Edit"),
    Delete(Color(0xFFDC2626), Icons.Rounded.Delete, "Delete"),
    View(Color(0xFF475569), Icons.Rounded.Visibility, "View"),
    Print(Color(0xFF0F766E), Icons.Rounded.Print, "Print"),
    Map(Color(0xFF7C3AED), Icons.Rounded.Map, "View Map"),
    Export(Color(0xFF0E7490), Icons.Rounded.Download, "Export"),
    Import(Color(0xFF4F46E5), Icons.Rounded.Upload, "Import"),
    Refresh(Color(0xFF2563EB), Icons.Rounded.Refresh, "Refresh"),
    Cancel(Color(0xFF64748B), Icons.Rounded.Close, "Cancel"),
    Recycle(Color(0xFF475569), Icons.Rounded.RestoreFromTrash, "Recycle Bin"),
}

/** Search and filter controls are neutral on purpose: a pale field with a dark icon, never a coloured button. */
object ActionColors {
    val SearchBackground = Color(0xFFE2E8F0)
    val SearchForeground = Color(0xFF334155)
}

/** The shared spacing scale for buttons, toolbars and record actions. */
object ActionSpacing {
    val Gap = 8.dp
    val IconGap = 8.dp
    val ButtonHeight = 44.dp
    val IconButtonSize = 40.dp
}

/** A solid round-cornered icon button for one record's action (View, Edit, Map, Print, Delete); 48 dp touch target. */
@Composable
fun ActionIconButton(
    kind: ActionKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = kind.label,
    enabled: Boolean = true,
    icon: ImageVector = kind.icon,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(ActionSpacing.IconButtonSize),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = kind.color, contentColor = Color.White),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}

/** A solid labelled button for toolbars and dialogs; [loading] shows a spinner and ignores taps so nothing is submitted twice. */
@Composable
fun ActionButton(
    kind: ActionKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = kind.label,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = { if (!loading) onClick() },
        modifier = modifier.heightIn(min = ActionSpacing.ButtonHeight),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = kind.color,
            contentColor = Color.White,
            disabledContainerColor = kind.color.copy(alpha = 0.45f),
            disabledContentColor = Color.White.copy(alpha = 0.8f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Icon(kind.icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(label, modifier = Modifier.padding(start = ActionSpacing.IconGap), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The module header: the title on the left, the actions on the right, wrapping onto the next line instead of overlapping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StandardToolbar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ActionSpacing.Gap),
        verticalArrangement = Arrangement.spacedBy(ActionSpacing.Gap),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f, fill = true).padding(end = ActionSpacing.Gap),
        )
        actions()
    }
}

/** The actions of one record in the standard order (View, Edit, Map, Print, Delete); wraps rather than covering the record's data. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StandardActionsRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(ActionSpacing.IconGap), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

/** The one search field: neutral background, dark search icon, a clear button when there is text, and a one-line placeholder. */
@Composable
fun StandardSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val container = if (dark) MaterialTheme.colorScheme.surfaceVariant else ActionColors.SearchBackground
    val foreground = if (dark) MaterialTheme.colorScheme.onSurfaceVariant else ActionColors.SearchForeground
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(12.dp),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = foreground) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = foreground) }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedTextColor = foreground,
            unfocusedTextColor = foreground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedPlaceholderColor = foreground.copy(alpha = 0.65f),
            unfocusedPlaceholderColor = foreground.copy(alpha = 0.65f),
        ),
    )
}
