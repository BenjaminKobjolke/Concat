// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

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
import androidx.compose.ui.graphics.asImageBitmap
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
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.api.ProjectView
import app.concat.editor.concat
import app.concat.editor.ui.components.AccentButton
import app.concat.editor.ui.components.ConcatIcon
import app.concat.editor.ui.components.IconAction
import app.concat.editor.ui.components.ToolButton
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.shortTimecode
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.ConcatType
import app.concat.editor.ui.theme.Dimens
import app.concat.editor.ui.theme.Motion
import app.concat.editor.ui.theme.Radii
import app.concat.editor.ui.theme.Spacing
import kotlinx.coroutines.delay

/** Which row of tools shows when nothing is selected. */
@Composable
internal fun InlineTextEditor(clip: Clip, frameWidth: Dp, frameHeight: Dp, onDone: (String) -> Unit) {
    var content by remember(clip.id) { mutableStateOf(clip.text?.optString("content") ?: clip.name) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(clip.id) { focus.requestFocus() }
    val colour = colourOf(clip.text?.optString("color", "#ffffff") ?: "#ffffff")
    val size = ((clip.text?.optDouble("fontSize", 0.07) ?: 0.07) * frameHeight.value * clip.scale).coerceIn(14.0, 40.0)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onDone(content) },
    ) {
        BasicTextField(
            value = content,
            onValueChange = { content = it },
            textStyle = ConcatType.title.copy(color = colour, fontSize = size.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(ConcatColors.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            // Where the title sits on the frame, upright, so the words are edited in place.
            modifier = Modifier.align(Alignment.Center)
                .offset(x = frameWidth * clip.offsetX.toFloat(), y = frameHeight * clip.offsetY.toFloat())
                .fillMaxWidth().padding(horizontal = Spacing.lg).focusRequester(focus),
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(Spacing.sm).size(34.dp).clip(CircleShape).background(ConcatColors.accent)
                .clickable(onClickLabel = "Done") { onDone(content) },
            contentAlignment = Alignment.Center,
        ) { ConcatIcon(ConcatIcons.Check, tint = ConcatColors.onAccent, size = 20.dp, label = "Done") }
    }
}

@Composable
internal fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ConcatColors.launchRaised,
        title = { Text("Rename project", style = ConcatType.title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Said over the preview once it draws the slower way: this phone's graphics
 * failed under it, so playback may stutter. Exports are not affected.
 */
@Composable
internal fun SlowerPreviewNote(modifier: Modifier) {
    Text(
        "Preview runs in a slower mode on this phone. Exports are not affected.",
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = ConcatType.caption,
        color = ConcatColors.fgMuted,
    )
}

/** The engine's picture: a SurfaceView it draws on, attached and detached with the surface's life. */
