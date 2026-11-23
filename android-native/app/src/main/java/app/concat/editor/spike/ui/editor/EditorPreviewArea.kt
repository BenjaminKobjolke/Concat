// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import android.app.Application
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ClipKind
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.concat
import app.concat.editor.spike.ui.components.AccentButton
import app.concat.editor.spike.ui.components.ConcatIcon
import app.concat.editor.spike.ui.components.IconAction
import app.concat.editor.spike.ui.components.ToolButton
import app.concat.editor.spike.ui.icons.ConcatIcons
import app.concat.editor.spike.ui.shortTimecode
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.ConcatType
import app.concat.editor.spike.ui.theme.Dimens
import app.concat.editor.spike.ui.theme.Motion
import app.concat.editor.spike.ui.theme.Radii
import app.concat.editor.spike.ui.theme.Spacing
import kotlinx.coroutines.delay

internal data class EditorPreviewActions(
    val onInlineEdit: (String?) -> Unit,
    val onSelectClip: (String?) -> Unit,
)

internal data class EditorPreviewState(
    val view: ProjectView?,
    val previewHeight: Dp,
    val ui: EditorUi,
    val model: EditorViewModel,
    val mainTrackId: String?,
    val inlineEdit: String?,
)

@Composable
internal fun EditorPreviewArea(
    state: EditorPreviewState,
    actions: EditorPreviewActions,
) {
        val view = state.view
        val previewHeight = state.previewHeight
        val ui = state.ui
        val model = state.model
        val mainTrackId = state.mainTrackId
        val inlineEdit = state.inlineEdit
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(previewHeight).padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (view != null) {
                val aspect = if (view.height > 0) view.width.toFloat() / view.height else 9f / 16f
                val roomW = maxWidth - 24.dp
                val roomH = maxHeight
                val (w, h) = if (roomW / roomH > aspect) Pair(roomH * aspect, roomH) else Pair(roomW, roomW / aspect)
                Box(Modifier.size(w, h).background(Color.Black)) {
                    PreviewSurface(model, Modifier.fillMaxSize())
                    PreviewOverlay(
                        view = view,
                        position = ui.position,
                        selected = if (inlineEdit != null) null else ui.selected,
                        mainTrackId = mainTrackId,
                        actions = PreviewOverlayActions(
                            onSelect = actions.onSelectClip,
                            onCommit = { id, p -> model.setTransform(id, p.scale, p.offsetX, p.offsetY, p.rotation) },
                            onEditText = {
                                model.pause()
                                actions.onInlineEdit(it)
                            },
                        ),
                    )
                    inlineEdit?.let { id ->
                        view.clip(id)?.let { clip ->
                            InlineTextEditor(clip, w, h, onDone = { content ->
                                val style = clip.text
                                model.saveText(
                                    clip.id, content,
                                    style?.optDouble("fontSize", 0.07) ?: 0.07,
                                    style?.optString("color", "#ffffff") ?: "#ffffff",
                                )
                                    actions.onInlineEdit(null)
                            })
                        }
                    }
                }
            }
        }


}
