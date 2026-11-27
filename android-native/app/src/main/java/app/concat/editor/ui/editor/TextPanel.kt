// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.concat.editor.api.Clip
import app.concat.editor.ui.components.ChoiceChip
import app.concat.editor.ui.components.PanelLabel
import app.concat.editor.ui.components.ToolPanel
import app.concat.editor.ui.components.ToolPanelOptions
import app.concat.editor.ui.components.ValueSlider
import app.concat.editor.ui.components.ValueSliderOptions
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.ConcatType
import app.concat.editor.ui.theme.Radii
import app.concat.editor.ui.theme.Spacing

data class TextPanelOptions(
    val existing: Clip?,
    val onSave: (content: String, size: Double, colour: String, weight: Double) -> Unit,
    val onClose: () -> Unit,
    val caption: Boolean = false,
)

@Composable
fun TextPanel(options: TextPanelOptions) {
    val (existing, onSave, onClose, caption) = options
    val style = existing?.text
    var content by remember(existing?.id) { mutableStateOf(style?.optString("content") ?: "") }
    // The document's size is a fraction of the frame's height.
    var size by remember(existing?.id) { mutableFloatStateOf((style?.optDouble("fontSize", 0.07) ?: 0.07).toFloat()) }
    var colour by remember(existing?.id) { mutableStateOf(style?.optString("color", "#ffffff") ?: "#ffffff") }
    var weight by remember(existing?.id) { mutableStateOf(style?.optDouble("fontWeight", 700.0) ?: 700.0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(existing?.id) { if (existing == null) focus.requestFocus() }
    fun save() {
        if (content.isNotBlank()) onSave(content.trim(), size.toDouble(), colour, weight)
        onClose()
    }

    ToolPanel(textPanelTitle(existing, caption), onClose = onClose, options = ToolPanelOptions(onConfirm = ::save)) {
        if (caption) PanelLabel("The words are this caption's; the look changes every caption.")
        BasicTextField(
            value = content,
            onValueChange = { content = it },
            textStyle = ConcatType.title.copy(color = ConcatColors.fg),
            cursorBrush = SolidColor(ConcatColors.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            decorationBox = { field ->
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.md)).background(ConcatColors.launchField)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                ) {
                    if (content.isEmpty()) Text("Enter text", style = ConcatType.title, color = ConcatColors.fgDim)
                    field()
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ChoiceChip("Regular", selected = weight < 600, onClick = { weight = 400.0 })
            ChoiceChip("Bold", selected = weight >= 600 && weight < 800, onClick = { weight = 700.0 })
            ChoiceChip("Black", selected = weight >= 800, onClick = { weight = 900.0 })
        }
        ValueSlider("Size", size, 0.03f..0.2f, options = ValueSliderOptions({ "${(it * 100).toInt()}" }, { size = it }))
        TextColourPicker(colour, onColourChange = { colour = it })
    }
}

private fun textPanelTitle(existing: Clip?, caption: Boolean) = when {
    existing == null -> "Add text"
    caption -> "Caption"
    else -> "Text"
}

@Composable
private fun TextColourPicker(colour: String, onColourChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        for (hex in TEXT_COLOURS) {
            val picked = hex.equals(colour, ignoreCase = true)
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .border(if (picked) 2.dp else 1.dp, if (picked) ConcatColors.accent else ConcatColors.lineStrong, CircleShape)
                    .padding(if (picked) Spacing.xs else 0.dp)
                    .clip(CircleShape)
                    .background(colourOf(hex))
                    .clickable { onColourChange(hex) },
            )
        }
    }
}
