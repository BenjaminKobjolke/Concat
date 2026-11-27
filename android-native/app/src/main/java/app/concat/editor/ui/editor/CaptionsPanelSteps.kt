// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.concat.editor.api.Clip
import app.concat.editor.api.ProjectView
import app.concat.editor.models.ModelStore
import app.concat.editor.models.ModelsState
import app.concat.editor.ui.Palette
import app.concat.editor.ui.theme.Spacing
import app.concat.editor.ui.timecode

internal data class CaptionsSheetData(
    val ui: CaptionsUi,
    val sound: List<Clip>,
    val models: ModelsState,
    val store: ModelStore,
    val captions: CaptionsViewModel,
    val view: ProjectView?,
    val editor: EditorViewModel,
    val onDismiss: () -> Unit,
)

@Composable
internal fun CaptionsSetup(data: CaptionsSheetData) {
    Column(
        Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (data.sound.isEmpty()) {
            SectionLabel("Add a video or a sound with speech in it first.")
        } else {
            SectionLabel("Clip")
            ChoiceChips(data.sound.map { it.id }, data.ui.clipId ?: "", { id ->
                val clip = data.sound.first { it.id == id }
                "${clip.name.ifBlank { clip.kind.name }.take(18)} at ${timecode(clip.start, data.view?.fps ?: 30.0)}"
            }, data.captions::chooseClip)
        }
        SectionLabel("Speech model")
        ModelPicker(
            ModelPickerOptions(
                data.models.info.transcribers,
                data.ui.model,
                data.models.downloads,
                { data.captions.chooseModel(it.id) },
                data.store::download,
            ),
        )
        val englishOnly = data.models.info.transcribers.firstOrNull { it.id == data.ui.model }?.englishOnly == true
        if (!englishOnly) {
            SectionLabel("Language")
            ChoiceChips(CAPTION_LANGUAGES, CAPTION_LANGUAGES.first { it.first == data.ui.language }, { it.second }) {
                data.captions.chooseLanguage(it.first)
            }
        }
        SectionLabel("Placement")
        ChoiceChips(listOf("top", "middle", "bottom"), data.ui.placement, { it.replaceFirstChar(Char::uppercase) }, data.captions::choosePlacement)
        SectionLabel("Size")
        ChoiceChips(listOf("small", "medium", "large"), data.ui.size, { it.replaceFirstChar(Char::uppercase) }, data.captions::chooseSize)
        Button(
            onClick = data.captions::transcribe,
            enabled = data.ui.clipId != null && data.models.info.transcribers.any { it.id == data.ui.model && it.downloaded },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Transcribe") }
    }
}

@Composable
internal fun CaptionsReview(data: CaptionsSheetData) {
    SectionLabel("${data.ui.segments.size} lines heard. Correct any before placing them.")
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        itemsIndexed(data.ui.segments) { index, segment ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timecode(segment.start, data.view?.fps ?: 30.0),
                    color = Palette.muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = Spacing.sm),
                )
                OutlinedTextField(
                    value = segment.text,
                    onValueChange = { data.captions.editSegment(index, it) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 3,
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedButton(onClick = data.captions::discard, modifier = Modifier.weight(1f)) { Text("Discard") }
        Button(
            onClick = { data.captions.place(data.editor, data.onDismiss) },
            enabled = data.ui.segments.any { it.text.isNotBlank() },
            modifier = Modifier.weight(1f),
        ) { Text("Place") }
    }
}
