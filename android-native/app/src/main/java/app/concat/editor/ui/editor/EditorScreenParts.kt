// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.MutableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.concat.editor.concat
import app.concat.editor.api.ProjectView
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.Dimens
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.concat.editor.api.Clip
import app.concat.editor.ui.theme.Motion

/** Where a tool's panel slides up from the toolbar, and back down. */
@Composable
internal fun PanelSlot(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(Motion.tweenMedium()) { it } + fadeIn(Motion.tweenShort()),
        exit = slideOutVertically(Motion.tweenShort()) { it } + fadeOut(Motion.tweenShort()),
    ) { content() }
}

@Composable
internal fun PreviewSurface(model: EditorViewModel, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {}
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        model.surfaceChanged(holder.surface, width, height)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        model.surfaceDestroyed()
                    }
                })
            }
        },
    )
}

internal data class EditorBackContext(
    val inlineEdit: String?,
    val fullscreen: Boolean,
    val panel: Panel?,
    val selected: String?,
    val menu: Menu,
    val clearInlineEdit: () -> Unit,
    val leaveFullscreen: () -> Unit,
    val closePanel: () -> Unit,
    val deselect: () -> Unit,
    val resetMenu: () -> Unit,
    val closeEditor: () -> Unit,
)

@Composable
internal fun EditorBackHandler(context: EditorBackContext) {
    BackHandler {
        when {
            context.inlineEdit != null -> context.clearInlineEdit()
            context.fullscreen -> context.leaveFullscreen()
            context.panel != null -> context.closePanel()
            context.selected != null -> context.deselect()
            context.menu != Menu.Root -> context.resetMenu()
            else -> context.closeEditor()
        }
    }
}

@Composable
internal fun ObserveEditorLifecycle(model: EditorViewModel) {
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
}

@Composable
internal fun ObserveEditorPanel(selected: Clip?, panel: Panel?, onPanel: (Panel?) -> Unit) {
    LaunchedEffect(selected?.id) {
        if (selected == null && panel in CLIP_PANELS) onPanel(null)
        if (selected != null && panel == Panel.TextNew) onPanel(null)
    }
}

internal data class EditorPickers(
    val addMedia: () -> Unit,
    val addOverlay: () -> Unit,
    val addAudio: () -> Unit,
    val replace: (Boolean) -> Unit,
    val cover: () -> Unit,
)

@Composable
internal fun rememberEditorPickers(model: EditorViewModel): EditorPickers {
    val visual = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { model.import(it, audio = false) }
    val overlay = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10), model::importOverlay)
    val audio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.import(it, audio = true) }
    val replacement = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), model::replace)
    val replacementSound = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::replace)
    val cover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), model::setCoverPicture)
    return EditorPickers(
        addMedia = { visual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        addOverlay = { overlay.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        addAudio = { audio.launch(arrayOf("audio/*")) },
        replace = { sound ->
            if (sound) replacementSound.launch(arrayOf("audio/*"))
            else replacement.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        },
        cover = { cover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )
}

internal data class EditorLayoutContext(
    val application: Application,
    val path: String,
    val onClosed: () -> Unit,
    val model: EditorViewModel,
    val ui: EditorUi,
    val view: ProjectView?,
    val selected: Clip?,
    val coverImage: ImageBitmap?,
    val mainTrackId: String?,
    val menu: MutableState<Menu>,
    val panel: MutableState<Panel?>,
    val shownPanel: MutableState<Panel?>,
    val fullscreen: MutableState<Boolean>,
    val inlineEdit: MutableState<String?>,
    val exporting: MutableState<Boolean>,
    val renaming: MutableState<Boolean>,
    val catalogueShelf: MutableState<Shelf?>,
    val catalogueAdjust: MutableState<Boolean>,
    val catalogue: CatalogueViewModel,
    val captions: CaptionsViewModel,
    val speech: SpeechViewModel,
    val voiceover: VoiceoverViewModel,
    val haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    val pickers: EditorPickers,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorScreenLayout(context: EditorLayoutContext) {
    var menu by context.menu
    var panel by context.panel
    var fullscreen by context.fullscreen
    var inlineEdit by context.inlineEdit
    var exporting by context.exporting
    var renaming by context.renaming
    var catalogueShelf by context.catalogueShelf
    var catalogueAdjust by context.catalogueAdjust
    val model = context.model
    val ui = context.ui
    val view = context.view
    val selected = context.selected
    val mainTrackId = context.mainTrackId
    val coverImage = context.coverImage
    val addMedia = context.pickers.addMedia
    BoxWithConstraints(Modifier.fillMaxSize().background(ConcatColors.page).safeDrawingPadding()) {
        val total = maxHeight
        val imeVisible = WindowInsets.isImeVisible
        val previewHeight: Dp = if (fullscreen) total - Dimens.topBar - Dimens.transport else total * Dimens.PREVIEW_SHARE
        Column(Modifier.fillMaxSize()) {
            EditorScreenHeader(context, previewHeight)
            EditorScreenWorkspace(context, imeVisible)
        }
        EditorMessage(ui.message, previewHeight, model::dismissMessage)
    }
    EditorModals(EditorModalContext(
        model = model, view = view, catalogue = context.catalogue, shelf = catalogueShelf,
        catalogueAdjust = catalogueAdjust, exporting = exporting, renaming = renaming,
        onCloseShelf = { catalogueShelf = null }, onCloseExport = { exporting = false },
        onCloseRename = { renaming = false },
    ))
}

@Composable
private fun ColumnScope.EditorScreenWorkspace(context: EditorLayoutContext, imeVisible: Boolean) {
    var menu by context.menu
    var panel by context.panel
    var catalogueShelf by context.catalogueShelf
    var catalogueAdjust by context.catalogueAdjust
    if (context.fullscreen.value) return
    val model = context.model
    val ui = context.ui
    val view = context.view
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
        val room = maxHeight
        if (view != null) {
            Timeline(TimelineContext(
                view = view, projectPath = context.path, media = context.application.concat.media,
                position = ui.position, selected = ui.selected, onScrub = model::scrubTo,
                onSelect = { model.select(it); if (it != null) menu = Menu.Root },
                onMove = model::move, onTrim = model::trim, onAddMedia = context.pickers.addMedia,
                onTransition = { model.select(it); panel = Panel.Transitions },
                onMuteTrack = model::setTrackSoundMuted,
                onCover = { model.select(null); panel = Panel.Cover }, cover = context.coverImage,
            ))
        }
        PanelSlot(visible = panel != null, modifier = Modifier.align(Alignment.BottomCenter)) {
            EditorPanelContent(context.shownPanel.value, room, EditorPanelContext(
                view = view, selected = context.selected, ui = ui, model = model,
                catalogue = context.catalogue, captions = context.captions,
                speech = context.speech, voiceover = context.voiceover,
                onClose = { panel = null }, onCoverPicture = context.pickers.cover,
                onCatalogue = { shelf, adjust -> catalogueAdjust = adjust; catalogueShelf = shelf },
            ))
        }
    }
    if (!imeVisible) {
        EditorToolRow(EditorToolRowContext(
            selected = context.selected, menu = menu, mainTrackId = context.mainTrackId, panel = panel,
            ui = ui, model = model, haptics = context.haptics,
            openPanel = { panel = if (panel == it) null else it }, setMenu = { menu = it },
            deselect = { model.select(null); panel = null }, addMedia = context.pickers.addMedia,
            addOverlay = context.pickers.addOverlay, addAudio = context.pickers.addAudio,
            replace = context.pickers.replace, selectMain = { model.selectAtPlayhead(context.mainTrackId) },
            split = model::split,
            catalogue = { shelf, adjust -> catalogueAdjust = adjust; catalogueShelf = shelf },
        ))
    }
}

@Composable
private fun EditorScreenHeader(context: EditorLayoutContext, previewHeight: Dp) {
    TopBar(TopBarContext(
        content = TopBarContent(context.view?.name ?: "", context.view?.let { resolutionLabel(it) } ?: "", context.view != null && context.view.clips.isNotEmpty()),
        actions = TopBarActions({ context.model.close(context.onClosed) }, { context.renaming.value = true }, { context.exporting.value = true }),
    ))
    EditorPreviewArea(EditorPreviewContext(
        view = context.view, height = previewHeight, model = context.model, ui = context.ui, mainTrackId = context.mainTrackId,
        inlineEdit = context.inlineEdit.value, onInlineEdit = { context.inlineEdit.value = it },
        onSelect = { context.model.select(it); if (it != null) context.menu.value = Menu.Root },
    ))
    EditorTransportRow(EditorTransportContext(
        ui = context.ui, view = context.view, selected = context.selected,
        fullscreen = context.fullscreen.value, model = context.model,
        onFullscreen = { context.fullscreen.value = !context.fullscreen.value },
    ))
}
