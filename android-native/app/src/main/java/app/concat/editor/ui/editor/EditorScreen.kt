// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import android.app.Application
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.concat.editor.concat
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.Dimens
internal data class EditorModels(
    val editor: EditorViewModel,
    val captions: CaptionsViewModel,
    val speech: SpeechViewModel,
    val voiceover: VoiceoverViewModel,
    val catalogue: CatalogueViewModel,
)

internal data class EditorLaunchContext(val path: String, val session: Int, val onClosed: () -> Unit, val startTool: String?)

internal data class EditorUiState(
    val menu: MutableState<Menu>,
    val panel: MutableState<Panel?>,
    val shownPanel: MutableState<Panel?>,
    val fullscreen: MutableState<Boolean>,
    val inlineEdit: MutableState<String?>,
    val exporting: MutableState<Boolean>,
    val renaming: MutableState<Boolean>,
    val catalogueShelf: MutableState<Shelf?>,
    val catalogueAdjust: MutableState<Boolean>,
)

@Composable
internal fun rememberEditorModels(application: Application, path: String, session: Int): EditorModels = EditorModels(
    editor = viewModel(key = "$path#$session", factory = viewModelFactory { initializer { EditorViewModel(application, path) } }),
    captions = viewModel(key = "$path#$session#captions", factory = viewModelFactory { initializer { CaptionsViewModel(application, path) } }),
    speech = viewModel(key = "$path#$session#speech", factory = viewModelFactory { initializer { SpeechViewModel(application, path) } }),
    voiceover = viewModel(key = "$path#$session#voiceover", factory = viewModelFactory { initializer { VoiceoverViewModel(application) } }),
    catalogue = viewModel(key = "catalogue", factory = viewModelFactory { initializer { CatalogueViewModel(application) } }),
)

@Composable
internal fun rememberEditorUiState(startTool: String?): EditorUiState {
    val menu = remember { mutableStateOf(Menu.Root) }
    val panel = remember {
        mutableStateOf(when (startTool) {
            "captions" -> Panel.Captions
            "speech" -> Panel.Speech
            else -> null
        })
    }
    val shownPanel = remember { mutableStateOf(panel.value) }
    if (panel.value != null) shownPanel.value = panel.value
    return EditorUiState(
        menu = menu, panel = panel, shownPanel = shownPanel,
        fullscreen = remember { mutableStateOf(false) },
        inlineEdit = remember { mutableStateOf<String?>(null) },
        exporting = remember { mutableStateOf(false) },
        renaming = remember { mutableStateOf(false) },
        catalogueShelf = remember { mutableStateOf<Shelf?>(null) },
        catalogueAdjust = remember { mutableStateOf(false) },
    )
}

@Composable
internal fun rememberEditorLayoutContext(
    application: Application,
    launch: EditorLaunchContext,
): EditorLayoutContext {
    val path = launch.path
    val session = launch.session
    val onClosed = launch.onClosed
    val models = rememberEditorModels(application, path, session)
    val model = models.editor
    val ui by model.ui.collectAsStateWithLifecycle()
    val screen = rememberEditorUiState(launch.startTool)
    val view = ui.view
    val selected = view?.clip(ui.selected)
    ObserveEditorLifecycle(model)
    EditorBackHandler(EditorBackContext(
        inlineEdit = screen.inlineEdit.value,
        fullscreen = screen.fullscreen.value,
        panel = screen.panel.value,
        selected = ui.selected,
        menu = screen.menu.value,
        clearInlineEdit = { screen.inlineEdit.value = null },
        leaveFullscreen = { screen.fullscreen.value = false },
        closePanel = { screen.panel.value = null },
        deselect = { model.select(null) },
        resetMenu = { screen.menu.value = Menu.Root },
        closeEditor = { model.close(onClosed) },
    ))
    ObserveEditorPanel(selected, screen.panel.value) { screen.panel.value = it }
    return EditorLayoutContext(
        application = application, path = path, onClosed = onClosed, model = model, ui = ui, view = view,
        selected = selected, coverImage = remember(ui.cover) { ui.cover?.asImageBitmap() },
        mainTrackId = view?.let { it.tracks.getOrNull(mainTrackIndex(it))?.id },
        menu = screen.menu, panel = screen.panel, shownPanel = screen.shownPanel,
        fullscreen = screen.fullscreen, inlineEdit = screen.inlineEdit, exporting = screen.exporting,
        renaming = screen.renaming, catalogueShelf = screen.catalogueShelf, catalogueAdjust = screen.catalogueAdjust,
        catalogue = models.catalogue, captions = models.captions, speech = models.speech,
        voiceover = models.voiceover, haptics = LocalHapticFeedback.current,
        pickers = rememberEditorPickers(model),
    )
}

/** Main editor screen for an open project. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(path: String, session: Int, onClosed: () -> Unit, startTool: String? = null) {
    val application = LocalContext.current.applicationContext as Application
    val context = rememberEditorLayoutContext(application, EditorLaunchContext(path, session, onClosed, startTool))
    EditorScreenLayout(context)
}
