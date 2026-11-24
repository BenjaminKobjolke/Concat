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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
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
    val models = rememberEditorScreenModels(application, path, session)
    val model = models.model
    val ui by model.ui.collectAsStateWithLifecycle()
    val menuState = remember { mutableStateOf(Menu.Root) }
    var menu by menuState
    val panelState = remember {
        mutableStateOf(initialEditorPanel(startTool))
    }
    var panel by panelState
    val catalogueShelfState = remember { mutableStateOf<Shelf?>(null) }
    val catalogueAdjustState = remember { mutableStateOf(false) }
    val exportingState = remember { mutableStateOf(false) }
    val renamingState = remember { mutableStateOf(false) }
    val fullscreenState = remember { mutableStateOf(false) }
    var fullscreen by fullscreenState
    val inlineEditState = remember { mutableStateOf<String?>(null) }
    var inlineEdit by inlineEditState

    // Pause when the app is covered; save when it is no longer seen.
    EditorLifecycleEffects(model)
    val transient = EditorScreenTransientState(
        menuState, panelState, catalogueShelfState, catalogueAdjustState,
        exportingState, renamingState, fullscreenState, inlineEditState,
    )
    BackHandler(onBack = editorBackAction(model, transient, ui.selected != null, onClosed))

    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        model.import(uris, audio = false)
    }
    val pickOverlay = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        model.importOverlay(uris)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.import(uris, audio = true)
    }
    val pickReplacement = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        model.replace(uri)
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        model.setCoverPicture(uri)
    }
    val pickReplacementSound = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        model.replace(uri)
    }
    fun addMedia() = pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))

    val view = ui.view
    val coverImage = remember(ui.cover) { ui.cover?.asImageBitmap() }
    val selected = view?.clip(ui.selected)
    // A clip's panels go when the clip does.
    LaunchedEffect(selected?.id) {
        if (selected == null && panel in CLIP_PANELS) panel = null
        if (selected != null && panel == Panel.TextNew) panel = null
    }

    EditorScreenContent(
        EditorScreenContentContext(
            models = models,
            ui = ui,
            transient = transient,
            actions = EditorScreenActions(onClosed, ::addMedia, {
                pickOverlay.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }, { pickAudio.launch(arrayOf("audio/*")) }, { sound ->
                if (sound) pickReplacementSound.launch(arrayOf("audio/*"))
                else pickReplacement.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }, {
                pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }),
        ),
    )
}

@Composable
private fun rememberEditorScreenModels(application: Application, path: String, session: Int): EditorScreenModels {
    val model: EditorViewModel = viewModel(
        key = "$path#$session",
        factory = viewModelFactory { initializer { EditorViewModel(application, path) } },
    )
    // These jobs outlive their panels, so keep one model of each per open project.
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
    return EditorScreenModels(application, path, model, captions, speech, voiceover, catalogue)
}

private fun initialEditorPanel(startTool: String?): Panel? = when (startTool) {
    "captions" -> Panel.Captions
    "speech" -> Panel.Speech
    else -> null
}

@Composable
private fun EditorLifecycleEffects(model: EditorViewModel) {
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

private fun editorBackAction(
    model: EditorViewModel,
    transient: EditorScreenTransientState,
    hasSelection: Boolean,
    onClosed: () -> Unit,
): () -> Unit = {
    when {
        transient.inlineEdit.value != null -> transient.inlineEdit.value = null
        transient.fullscreen.value -> transient.fullscreen.value = false
        transient.panel.value != null -> transient.panel.value = null
        hasSelection -> model.select(null)
        transient.menu.value != Menu.Root -> transient.menu.value = Menu.Root
        else -> model.close(onClosed)
    }
}

