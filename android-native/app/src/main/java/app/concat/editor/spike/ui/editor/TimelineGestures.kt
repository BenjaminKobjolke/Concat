// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import app.concat.editor.spike.api.ProjectView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
internal data class TimelineGestureContext(
    val pxPerSecond: MutableFloatState,
    val drag: MutableState<DragPreview?>,
    val fling: MutableState<Job?>,
    val scrollY: MutableState<Float?>,
    val scope: CoroutineScope,
    val haptics: HapticFeedback,
    val knob: Animatable<Float, AnimationVector1D>,
    val currentView: State<ProjectView>,
    val currentPosition: State<Double>,
    val currentSelected: State<String?>,
    val scrub: State<(Double) -> Unit>,
    val choose: State<(String?) -> Unit>,
    val move: State<(String, Double, LaneTarget) -> Unit>,
    val trim: State<(String, String, Double) -> Unit>,
    val addMedia: State<() -> Unit>,
    val transition: State<(String) -> Unit>,
    val muteTrack: State<(String, Boolean) -> Unit>,
)


internal fun Modifier.timelinePointerInput(input: TimelineGestureContext) = pointerInput(Unit) {
    TimelineGestureController(input).run()
}