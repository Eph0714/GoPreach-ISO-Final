package com.emfitsolutions.gopreach.ui.screens.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.repository.QuickLoginMethod
import com.emfitsolutions.gopreach.domain.QuickLoginPolicy

/**
 * A 3x3 unlock-pattern pad. Drag across the dots; when the finger lifts, [onComplete] gets the dots
 * in the order they were touched (0..8, row by row). Nothing is remembered on screen afterwards.
 */
@Composable
fun PatternLockPad(onComplete: (List<Int>) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val selected = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outline

    fun centerOf(i: Int): Offset {
        val cell = size.width / 3f
        return Offset(cell * (i % 3) + cell / 2, cell * (i / 3) + cell / 2)
    }
    fun hit(p: Offset): Int? {
        val reach = size.width / 3f * 0.38f
        return (0 until 9).firstOrNull { (centerOf(it) - p).getDistance() <= reach }
    }

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .onSizeChanged { size = it }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { start ->
                        selected.clear()
                        finger = start
                        hit(start)?.let(selected::add)
                    },
                    onDrag = { change, _ ->
                        finger = change.position
                        hit(change.position)?.let { if (it !in selected) selected.add(it) }
                    },
                    onDragEnd = {
                        val drawn = selected.toList()
                        selected.clear()
                        finger = null
                        if (drawn.isNotEmpty()) onComplete(drawn)
                    },
                    onDragCancel = { selected.clear(); finger = null },
                )
            },
    ) {
        val lineWidth = 6.dp.toPx()
        selected.zipWithNext().forEach { (a, b) -> drawLine(active, centerOf(a), centerOf(b), lineWidth, StrokeCap.Round) }
        val tip = finger
        if (selected.isNotEmpty() && tip != null) drawLine(active.copy(alpha = 0.5f), centerOf(selected.last()), tip, lineWidth, StrokeCap.Round)
        for (i in 0 until 9) {
            val on = i in selected
            drawCircle(if (on) active else idle, radius = if (on) 14.dp.toPx() else 9.dp.toPx(), center = centerOf(i))
            if (on) drawCircle(active.copy(alpha = 0.25f), radius = 28.dp.toPx(), center = centerOf(i), style = Stroke(width = 3.dp.toPx()))
        }
    }
}

private fun lockMessage(ms: Long): String {
    val seconds = (ms + 999) / 1000
    return if (seconds >= 60) "Too many wrong tries. Try again in ${(seconds + 59) / 60} min." else "Too many wrong tries. Try again in $seconds sec."
}

/** Sign-in with a PIN or Pattern. [error]/[lockedMs] come from the last attempt. */
@Composable
fun QuickLoginDialog(
    method: QuickLoginMethod,
    username: String?,
    error: String?,
    lockedMs: Long,
    busy: Boolean,
    onSubmit: (secret: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val locked = lockedMs > 0
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Login with ${method.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (!username.isNullOrBlank()) Text("Signing in as $username", style = MaterialTheme.typography.bodyMedium)
                when (method) {
                    QuickLoginMethod.PIN -> OutlinedTextField(
                        value = pin,
                        onValueChange = { v ->
                            pin = v.filter(Char::isDigit).take(QuickLoginPolicy.PIN_LENGTH)
                            if (pin.length == QuickLoginPolicy.PIN_LENGTH && !locked && !busy) { onSubmit(pin); pin = "" }
                        },
                        label = { Text("PIN") },
                        singleLine = true,
                        enabled = !locked && !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    QuickLoginMethod.PATTERN -> {
                        Text("Draw your pattern", style = MaterialTheme.typography.bodySmall)
                        PatternLockPad(
                            onComplete = { onSubmit(QuickLoginPolicy.patternSecret(it)) },
                            enabled = !locked && !busy,
                            modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().padding(8.dp),
                        )
                    }
                }
                val message = if (locked) lockMessage(lockedMs) else error
                if (message != null) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/**
 * Creates a PIN or Pattern: pick it, then repeat it to confirm. [onConfirmed] gets the secret only after
 * it passed [QuickLoginPolicy] and was entered the same way twice.
 */
@Composable
fun QuickLoginSetupDialog(method: QuickLoginMethod, onConfirmed: (secret: String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }

    fun submit(secret: String, problem: String?) {
        error = null
        val firstEntry = first
        when {
            firstEntry == null -> if (problem != null) error = problem else { first = secret; pin = "" }
            firstEntry != secret -> { error = "They don't match. Start again."; first = null; pin = "" }
            else -> onConfirmed(secret)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (first == null) "Create ${method.label}" else "Confirm ${method.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when (method) {
                    QuickLoginMethod.PIN -> {
                        Text(
                            if (first == null) "Choose a ${QuickLoginPolicy.PIN_LENGTH}-digit PIN. Avoid easy ones like 123456 or 000000."
                            else "Enter the same PIN again.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { v ->
                                pin = v.filter(Char::isDigit).take(QuickLoginPolicy.PIN_LENGTH)
                                if (pin.length == QuickLoginPolicy.PIN_LENGTH) submit(pin, QuickLoginPolicy.pinProblem(pin))
                            },
                            label = { Text(if (first == null) "New PIN" else "Confirm PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    QuickLoginMethod.PATTERN -> {
                        Text(
                            if (first == null) "Draw a pattern connecting at least ${QuickLoginPolicy.PATTERN_MIN_NODES} dots."
                            else "Draw the same pattern again.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        PatternLockPad(
                            onComplete = { submit(QuickLoginPolicy.patternSecret(it), QuickLoginPolicy.patternProblem(it)) },
                            modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().padding(8.dp),
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
