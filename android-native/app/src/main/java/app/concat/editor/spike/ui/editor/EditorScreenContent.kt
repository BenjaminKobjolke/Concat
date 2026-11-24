// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.concat.editor.spike.concat
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.ConcatType
import app.concat.editor.spike.ui.theme.Dimens
import app.concat.editor.spike.ui.theme.Radii
import app.concat.editor.spike.ui.theme.Spacing
import kotlinx.coroutines.delay

internal data class EditorScreenModels(
    val application: Application,
    val path: String,
    val model: EditorViewModel,
    val captions: CaptionsViewModel,
    val speech: SpeechViewModel,
    val voiceover: VoiceoverViewModel,
    val catalogue: CatalogueViewModel,
)

internal data class EditorScreenTransientState(
    val menu: androidx.compose.runtime.MutableState<Menu>,
    val panel: androidx.compose.runtime.MutableState<Panel?>,
    val catalogueShelf: androidx.compose.runtime.MutableState<Shelf?>,
    val catalogueAdjust: androidx.compose.runtime.MutableState<Boolean>,
    val exporting: androidx.compose.runtime.MutableState<Boolean>,
    val renaming: androidx.compose.runtime.MutableState<Boolean>,
    val fullscreen: androidx.compose.runtime.MutableState<Boolean>,
    val inlineEdit: androidx.compose.runtime.MutableState<String?>,
)

internal data class EditorScreenActions(
    val onClosed: () -> Unit,
    val addMedia: () -> Unit,
    val addOverlay: () -> Unit,
    val addAudio: () -> Unit,
    val replace: (Boolean) -> Unit,
    val onPickCover: () -> Unit,
)

internal data class EditorScreenContentContext(
    val models: EditorScreenModels,
    val ui: EditorUi,
    val transient: EditorScreenTransientState,
    val actions: EditorScreenActions,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorScreenContent(state: EditorScreenContentContext) {
    val application = state.models.application
    val path = state.models.path
    val model = state.models.model
    val captions = state.models.captions
    val speech = state.models.speech
    val voiceover = state.models.voiceover
    val catalogue = state.models.catalogue
    val ui = state.ui
    val onClosed = state.actions.onClosed
    var menu by state.transient.menu
    var catalogueShelf by state.transient.catalogueShelf
    var catalogueAdjust by state.transient.catalogueAdjust
    var exporting by state.transient.exporting
    var renaming by state.transient.renaming
    var fullscreen by state.transient.fullscreen
    var inlineEdit by state.transient.inlineEdit
    val view = ui.view
    val selected = view?.clip(ui.selected)
    val mainTrackId = view?.let { v -> v.tracks.getOrNull(mainTrackIndex(v))?.id }
    BoxWithConstraints(Modifier.fillMaxSize().background(ConcatColors.page).safeDrawingPadding()) {
        val total = maxHeight
        val imeVisible = WindowInsets.isImeVisible
        val previewHeight: Dp = if (fullscreen) total - Dimens.topBar - Dimens.transport else total * Dimens.PREVIEW_SHARE
        Column(Modifier.fillMaxSize()) {
            TopBar(
                name = view?.name ?: "",
                resolution = view?.let { resolutionLabel(it) } ?: "",
                canExport = view != null && view.clips.isNotEmpty(),
                onClose = { model.close(onClosed) },
                onRename = { renaming = true },
                onExport = { exporting = true },
            )

            EditorPreviewArea(
                state = EditorPreviewState(view, previewHeight, ui, model, mainTrackId, inlineEdit),
                actions = EditorPreviewActions(
                    onInlineEdit = { inlineEdit = it },
                    onSelectClip = {
                        model.select(it)
                        if (it != null) menu = Menu.Root
                    },
                ),
            )

            Transport(
                state = TransportState(
                    position = ui.position,
                    duration = view?.duration ?: 0.0,
                    playing = ui.playing,
                    canUndo = view?.canUndo == true,
                    canRedo = view?.canRedo == true,
                    keyframe = selected?.takeIf { it.placeable }?.let { clip ->
                        val at = ((ui.position - clip.start) / clip.duration.coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                        clip.keyedAt(at, 0.5 / ((view?.fps ?: 30.0) * clip.duration.coerceAtLeast(1e-6)))
                    },
                    fullscreen = fullscreen,
                ),
                actions = TransportActions(
                    onPlay = model::togglePlay,
                    onUndo = model::undo,
                    onRedo = model::redo,
                    onKeyframe = model::toggleKeyframe,
                    onFullscreen = { fullscreen = !fullscreen },
                ),
            )

            EditorScreenTimelineAndToolbar(state, view, selected, mainTrackId, imeVisible)
        }

        // What the editor has to say, for a moment, over the bottom of the preview.
        ui.message?.let { message ->
            LaunchedEffect(message) {
                delay(3500)
                model.dismissMessage()
            }
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = Dimens.topBar + previewHeight - 52.dp).padding(horizontal = Spacing.xl)
                    .clip(RoundedCornerShape(Radii.lg)).background(ConcatColors.raised).clickable { model.dismissMessage() }
                    .padding(horizontal = Spacing.lg, vertical = 10.dp),
            ) { Text(message, style = ConcatType.label, color = ConcatColors.fg, textAlign = TextAlign.Center) }
        }
    }

    catalogueShelf?.let { shelf ->
        CatalogueSheet(shelf, catalogue, model, catalogueAdjust, onDismiss = { catalogueShelf = null })
    }
    if (exporting) ExportSheet(model, onDismiss = { exporting = false })
    if (renaming && view != null) {
        RenameDialog(view.name, onDismiss = { renaming = false }) {
            model.rename(it)
            renaming = false
        }
    }
}
