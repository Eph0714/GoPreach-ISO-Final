package com.emfitsolutions.gopreach.ui.screens.bibletext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.domain.NwtBibleReferenceData

/**
 * "My Bible Text Record: Event/Topic Record Structure Upgrade" spec §3/§4/
 * §22 — Bible Book selection as boxes/cards in a responsive grid, never a
 * dropdown. [FlowRow] wraps naturally to the next row at any width (spec's
 * own "4-6 per row desktop / 3-4 tablet / 2 mobile" is exactly what a
 * fixed-minimum-width box in a wrapping row does on its own — no manual
 * breakpoint math needed), and every box keeps a consistent min size (spec
 * §22: "consistent minimum height and width... easy to tap on mobile").
 *
 * [search] narrows the list client-side (spec: "Support search or filtering
 * if the number of books makes navigation difficult") while always keeping
 * canonical [NwtBibleReferenceData.BibleBook.order] — filtering never
 * reorders.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BibleBookGrid(
    books: List<NwtBibleReferenceData.BibleBook>,
    selectedBookId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showSearch: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(books, query) {
        if (query.isBlank()) books else books.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showSearch && books.size > 12) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search books...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (filtered.isEmpty()) {
            Text("No matching Bible books.", style = MaterialTheme.typography.bodySmall)
        } else {
            // Two groups, each under its own heading — a heading only appears when
            // it has books (a search can leave just one testament).
            listOf(
                NwtBibleReferenceData.Testament.OLD to "Old Testament",
                NwtBibleReferenceData.Testament.NEW to "New Testament",
            ).forEach { (testament, heading) ->
                val group = filtered.filter { it.testament == testament }
                if (group.isNotEmpty()) {
                    Text(heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { book ->
                            SelectableBox(
                                label = book.name,
                                selected = book.id == selectedBookId,
                                onClick = { onSelect(book.id) },
                                minWidth = 100.dp,
                                minHeight = 48.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chapter selection as boxes/cards (spec §3/§4/§22) — [chapterCount] drives
 * exactly how many boxes render (1..chapterCount), so an invalid chapter for
 * the selected book can never even be shown, let alone tapped. The caller
 * (see [BibleTextRecordDialog]) resets the selected chapter to null whenever
 * the selected book changes, before this recomposes with the new
 * [chapterCount] — never this composable's own job, since it has no way to
 * tell "book just changed" from "still the same book, chapter count
 * unchanged" on its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChapterGrid(
    chapterCount: Int,
    selectedChapter: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (chapter in 1..chapterCount) {
            SelectableBox(
                label = chapter.toString(),
                selected = chapter == selectedChapter,
                onClick = { onSelect(chapter) },
                minWidth = 44.dp,
                minHeight = 44.dp,
            )
        }
    }
}

/** One box/card — default/hover/selected/disabled visual states (spec §4),
 * selection shown via BOTH a filled primary background/border AND a
 * checkmark icon (spec §22: "Not rely only on color to show selection"),
 * with `Role.RadioButton`/`selected` semantics for screen readers (spec §22
 * accessibility requirements) since exactly one box in the group is ever
 * chosen at a time, same as a radio group. */
@Composable
private fun SelectableBox(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    minWidth: Dp,
    minHeight: Dp,
) {
    val background = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { role = Role.RadioButton; this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = contentColor, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
            if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = contentColor, modifier = Modifier.padding(start = 2.dp))
        }
    }
}
