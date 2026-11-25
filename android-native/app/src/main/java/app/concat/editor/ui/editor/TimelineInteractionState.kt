// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import app.concat.editor.api.ProjectView
import app.concat.editor.media.MediaCache
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** State and current callbacks used by the timeline's long-lived pointer handler. */
internal data class TimelineInteractionState(
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
    val openCover: State<() -> Unit>,
)

internal data class TimelineContext(
    val view: ProjectView,
    val projectPath: String,
    val media: MediaCache,
    val position: Double,
    val selected: String?,
    val onScrub: (Double) -> Unit,
    val onSelect: (String?) -> Unit,
    val onMove: (clipId: String, start: Double, target: LaneTarget) -> Unit,
    val onTrim: (clipId: String, edge: String, delta: Double) -> Unit,
    val onAddMedia: () -> Unit,
    val onTransition: (nextClipId: String) -> Unit,
    val onMuteTrack: (trackId: String, muted: Boolean) -> Unit,
    val onCover: () -> Unit,
    val modifier: Modifier = Modifier,
    val cover: ImageBitmap? = null,
)

internal data class TimelineDrawState(
    val pxPerSecond: MutableFloatState,
    val drag: MutableState<DragPreview?>,
    val fling: MutableState<Job?>,
    val scroll: MutableState<Float?>,
    val scope: CoroutineScope,
    val haptics: HapticFeedback,
    val measurer: TextMeasurer,
    val selection: Animatable<Float, AnimationVector1D>,
    val knob: Animatable<Float, AnimationVector1D>,
    val glyphs: TimelineGlyphs,
)

@Composable
internal fun rememberTimelineDrawState(selected: String?): TimelineDrawState {
    val pxPerSecond = remember { mutableFloatStateOf(0f) }
    val drag = remember { mutableStateOf<DragPreview?>(null) }
    val fling = remember { mutableStateOf<Job?>(null) }
    val scroll = remember { mutableStateOf<Float?>(null) }
    val selection = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected != null) {
            selection.snapTo(0f)
            selection.animateTo(1f, Motion.tweenShort())
        }
    }
    return TimelineDrawState(
        pxPerSecond = pxPerSecond,
        drag = drag,
        fling = fling,
        scroll = scroll,
        scope = rememberCoroutineScope(),
        haptics = LocalHapticFeedback.current,
        measurer = rememberTextMeasurer(),
        selection = selection,
        knob = remember { Animatable(0f) },
        glyphs = rememberTimelineGlyphs(),
    )
}

@Composable
internal fun rememberTimelineGlyphs() = TimelineGlyphs(
    text = rememberVectorPainter(ConcatIcons.TextMark),
    music = rememberVectorPainter(ConcatIcons.Music),
    image = rememberVectorPainter(ConcatIcons.Image),
    film = rememberVectorPainter(ConcatIcons.Film),
    transition = rememberVectorPainter(ConcatIcons.Transition),
    sparkle = rememberVectorPainter(ConcatIcons.Sparkle),
    plus = rememberVectorPainter(ConcatIcons.Plus),
    volume = rememberVectorPainter(ConcatIcons.Volume),
    volumeOff = rememberVectorPainter(ConcatIcons.VolumeOff),
    filter = rememberVectorPainter(ConcatIcons.Filter),
    shapes = rememberVectorPainter(ConcatIcons.Shapes),
)
