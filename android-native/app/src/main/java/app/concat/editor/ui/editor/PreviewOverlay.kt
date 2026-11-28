// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.concat.editor.api.ClipBounds
import app.concat.editor.api.ProjectView
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.theme.Motion
import androidx.compose.ui.graphics.vector.rememberVectorPainter
data class PreviewOverlayContent(val view: ProjectView, val position: Double, val selected: String?, val mainTrackId: String?, val bounds: Map<String, ClipBounds>)

/**
 * Editing on the preview: tap a title, picture or overlay to select it;
 * one finger drags it, two pinch and turn it, and a corner handle scales
 * and turns it about its centre.
 */
@Composable
fun PreviewOverlay(
    content: PreviewOverlayContent,
    onSelect: (String?) -> Unit,
    onCommit: (clipId: String, placement: Placement) -> Unit,
    onEditText: (clipId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = content.view
    val position = content.position
    val selected = content.selected
    val bounds = content.bounds
    val haptics = LocalHapticFeedback.current
    val liveState = remember { mutableStateOf<Pair<String, Placement>?>(null) }
    var live by liveState
    val guidesState = remember { mutableStateOf(false to false) }
    var guides by guidesState
    val movingState = remember { mutableStateOf(false) }
    var moving by movingState
    val lastTapState = remember { mutableStateOf("" to 0L) }
    var lastTap by lastTapState
    val currentView = rememberUpdatedState(view)
    val currentBounds = rememberUpdatedState(bounds)
    val currentPosition = rememberUpdatedState(position)
    val currentSelected = rememberUpdatedState(selected)
    val select = rememberUpdatedState(onSelect)
    val commit = rememberUpdatedState(onCommit)
    val editText = rememberUpdatedState(onEditText)
    val rotateGlyph = rememberVectorPainter(ConcatIcons.Rotate)
    val appear = remember { Animatable(0f) }

    LaunchedEffect(selected) {
        appear.snapTo(0f)
        if (selected != null) appear.animateTo(1f, Motion.tweenShort())
    }
    val committed = currentView.value.clip(live?.first)?.let { Placement.of(it) }
    LaunchedEffect(committed) { if (!moving) live = null }

    val gesture = OverlayGestureContext(
        currentView,
        currentBounds,
        currentPosition,
        currentSelected,
        rememberUpdatedState(content.mainTrackId),
        liveState,
        guidesState,
        movingState,
        lastTapState,
        { select.value(it) },
        { id, placement -> commit.value(id, placement) },
        { editText.value(it) },
        haptics,
    )

    Canvas(modifier.fillMaxSize().previewGestures(gesture)) {
        drawPreviewOverlay(OverlayDrawState(view, position, selected, bounds, live, moving, guides, appear.value, rotateGlyph))
    }
}
