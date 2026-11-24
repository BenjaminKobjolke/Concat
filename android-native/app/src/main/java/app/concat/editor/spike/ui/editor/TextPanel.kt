// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.concat.editor.spike.api.AppliedEffect
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ClipKind
import app.concat.editor.spike.api.PackageInfo
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.ui.components.ChoiceChip
import app.concat.editor.spike.ui.components.ConcatIcon
import app.concat.editor.spike.ui.components.GhostButton
import app.concat.editor.spike.ui.components.PanelLabel
import app.concat.editor.spike.ui.components.ToolPanel
import app.concat.editor.spike.ui.components.ToolPanelLayout
import app.concat.editor.spike.ui.components.ValueSlider
import app.concat.editor.spike.ui.icons.ConcatIcons
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.ConcatType
import app.concat.editor.spike.ui.theme.Radii
import app.concat.editor.spike.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import org.json.JSONObject

val TEXT_COLOURS = listOf("#ffffff", "#000000", "#cbf53f", "#ffd60a", "#ff9f0a", "#ff375f", "#bf5af2", "#0a84ff", "#30d158")

fun colourOf(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color.White
}

internal data class TextPanelContext(
    val existing: Clip?,
    val onSave: (content: String, size: Double, colour: String, weight: Double) -> Unit,
    val onClose: () -> Unit,
    val caption: Boolean = false,
)

/**
 * A title's words and look: a new one (at the playhead) or [existing]
 * restyled. The words in a field that takes the keyboard at once.
 */
@Composable
internal fun TextPanel(context: TextPanelContext) {
    val existing = context.existing
    val style = existing?.text
    var content by remember(existing?.id) { mutableStateOf(style?.optString("content") ?: "") }
    // The document's size is a fraction of the frame's height.
    var size by remember(existing?.id) { mutableFloatStateOf((style?.optDouble("fontSize", 0.07) ?: 0.07).toFloat()) }
    var colour by remember(existing?.id) { mutableStateOf(style?.optString("color", "#ffffff") ?: "#ffffff") }
    var weight by remember(existing?.id) { mutableStateOf(style?.optDouble("fontWeight", 700.0) ?: 700.0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(existing?.id) { if (existing == null) focus.requestFocus() }
    fun save() {
        if (content.isNotBlank()) context.onSave(content.trim(), size.toDouble(), colour, weight)
        context.onClose()
    }

    ToolPanel(
        if (existing == null) "Add text" else if (context.caption) "Caption" else "Text",
        onClose = context.onClose,
        onConfirm = ::save,
    ) {
        if (context.caption) PanelLabel("The words are this caption's; the look changes every caption.")
        TextPanelInput(content, focus) { content = it }
        TextPanelWeightOptions(weight) { weight = it }
        ValueSlider("Size", size, 0.03f..0.2f, { "${(it * 100).toInt()}" }, onChange = { size = it })
        TextPanelColourOptions(colour) { colour = it }
    }
}

@Composable
private fun TextPanelInput(content: String, focus: FocusRequester, onChange: (String) -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    BasicTextField(
        value = content,
        onValueChange = onChange,
        textStyle = ConcatType.title.copy(color = ConcatColors.fg),
        cursorBrush = SolidColor(ConcatColors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        maxLines = 4,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { isFocused = it.isFocused },
        decorationBox = { field ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.md)).background(ConcatColors.launchField)
                    .border(if (isFocused) 2.dp else 0.dp, ConcatColors.accent, RoundedCornerShape(Radii.md))
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
            ) {
                if (content.isEmpty()) Text("Enter text", style = ConcatType.title, color = ConcatColors.fgDim)
                field()
            }
        },
    )
}

@Composable
private fun TextPanelWeightOptions(weight: Double, onSelect: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ChoiceChip("Regular", selected = weight < 600, onClick = { onSelect(400.0) })
        ChoiceChip("Bold", selected = weight >= 600 && weight < 800, onClick = { onSelect(700.0) })
        ChoiceChip("Black", selected = weight >= 800, onClick = { onSelect(900.0) })
    }
}

@Composable
private fun TextPanelColourOptions(colour: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (hex in TEXT_COLOURS) {
            val picked = hex.equals(colour, ignoreCase = true)
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .border(if (picked) 2.dp else 1.dp, if (picked) ConcatColors.accent else ConcatColors.lineStrong, CircleShape)
                    .padding(if (picked) 4.dp else 0.dp)
                    .clip(CircleShape)
                    .background(colourOf(hex))
                    .clickable { onSelect(hex) },
            )
        }
    }
}
