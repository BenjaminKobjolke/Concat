// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

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
import androidx.compose.ui.platform.LocalHapticFeedback
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
/**
 * The editor, CapCut's way: a top bar, the preview (at most about 42% of
 * the height: the timeline is the product), the transport, the timeline,
 * and a scrollable toolbar whose tools open their options in a panel
 * above it. [startTool] opens a panel at once ("captions", "speech").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(path: String, session: Int, onClosed: () -> Unit, startTool: String? = null) {
    val application = LocalContext.current.applicationContext as Application
    val model: EditorViewModel = viewModel(
        key = "$path#$session",
        factory = viewModelFactory { initializer { EditorViewModel(application, path) } },
    )
    // The panels' own models, one each per open project: their jobs outlive the panel.
    val captions: CaptionsViewModel = viewModel(
        key = "$path#$session#captions",
        factory = viewModelFactory { initializer { CaptionsViewModel(application, path) } },
    )
    val speech: SpeechViewModel = viewModel(
        key = "$path#$session#speech",
        factory = viewModelFactory { initializer { SpeechViewModel(application, path) } },
    )
    val voiceover: VoiceoverViewModel = viewModel(
        key = "$path#$session#voiceover",
        factory = viewModelFactory { initializer { VoiceoverViewModel(application) } },
    )
    val catalogue: CatalogueViewModel = viewModel(
        key = "catalogue",
        factory = viewModelFactory { initializer { CatalogueViewModel(application) } },
    )
    val ui by model.ui.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(Menu.Root) }
    var panel by remember {
        mutableStateOf(
            when (startTool) {
                "captions" -> Panel.Captions
                "speech" -> Panel.Speech
                else -> null
            },
        )
    }
    var shownPanel by remember { mutableStateOf(panel) }
    if (panel != null) shownPanel = panel
    var catalogueShelf by remember { mutableStateOf<Shelf?>(null) }
    var catalogueAdjust by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var inlineEdit by remember { mutableStateOf<String?>(null) }

    // Pause when the app is covered; save when it is no longer seen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> model.pause()
                Lifecycle.Event.ON_STOP -> model.save()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler {
        when {
            inlineEdit != null -> inlineEdit = null
            fullscreen -> fullscreen = false
            panel != null -> panel = null
            ui.selected != null -> model.select(null)
            menu != Menu.Root -> menu = Menu.Root
            else -> model.close(onClosed)
        }
    }

    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        model.import(uris, audio = false)
    }
    val pickOverlay = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        model.importOverlay(uris)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.import(uris, audio = true)
    }
    fun addMedia() = pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))

    val view = ui.view
    val selected = view?.clip(ui.selected)
    val mainTrackId = view?.let { v -> v.tracks.getOrNull(mainTrackIndex(v))?.id }

    // A clip's panels go when the clip does.
    LaunchedEffect(selected?.id) {
        if (selected == null && panel in CLIP_PANELS) panel = null
        if (selected != null && panel == Panel.TextNew) panel = null
    }

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
                view = view,
                previewHeight = previewHeight,
                ui = ui,
                model = model,
                mainTrackId = mainTrackId,
                inlineEdit = inlineEdit,
                actions = EditorPreviewActions(
                    onInlineEdit = { inlineEdit = it },
                    onSelectClip = {
                        model.select(it)
                        if (it != null) menu = Menu.Root
                    },
                ),
            )

            Transport(
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
                onPlay = model::togglePlay,
                onUndo = model::undo,
                onRedo = model::redo,
                onKeyframe = model::toggleKeyframe,
                onFullscreen = { fullscreen = !fullscreen },
            )

            if (!fullscreen) {
                // Clipped, so a panel sliding up never draws over the toolbar.
                EditorTimelineArea(
                    state = EditorTimelineState(
                        view = view,
                        projectPath = path,
                        media = application.concat.media,
                        position = ui.position,
                        selected = ui.selected,
                        panel = panel,
                        model = model,
                        panelContext = EditorPanelContext(
                            view, selected, model, captions, speech, voiceover, catalogue,
                            EditorPanelActions(
                                onClose = { panel = null },
                                onOpenCatalogue = { shelf ->
                                    catalogueAdjust = false
                                    catalogueShelf = shelf
                                },
                            ),
                        ),
                    ),
                    actions = EditorTimelineActions(
                        onScrub = model::scrubTo,
                        onSelect = {
                            model.select(it)
                            if (it != null) menu = Menu.Root
                        },
                        onMove = model::move,
                        onTrim = model::trim,
                        onAddMedia = ::addMedia,
                        onTransition = { next ->
                            model.select(next)
                            panel = Panel.Transitions
                        },
                        onMuteTrack = model::setTrackSoundMuted,
                    ),
                    modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                )

                if (!imeVisible) {
                    val tools = toolsFor(
                        selected = selected,
                        menu = menu,
                        mainTrackId = mainTrackId,
                        panel = panel,
                        actions = ToolActions(
                            openPanel = { panel = if (panel == it) null else it },
                            setMenu = { menu = it },
                            deselect = {
                                model.select(null)
                                panel = null
                            },
                            addMedia = ::addMedia,
                            addOverlay = { pickOverlay.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                            addAudio = { pickAudio.launch(arrayOf("audio/*")) },
                            selectMain = { model.selectAtPlayhead(mainTrackId) },
                            split = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                model.split()
                            },
                            catalogue = { shelf, adjust ->
                                catalogueAdjust = adjust
                                catalogueShelf = shelf
                            },
                            model = model,
                        ),
                    )
                    Toolbar(tools, key = "${selected?.kind}-${selected != null}-$menu")
                }
            }
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

