// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.concat.editor.concat
import app.concat.editor.ui.Palette
import app.concat.editor.ui.components.ToolPanel
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.Spacing
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.concat.editor.ui.components.ToolPanelOptions

sealed interface VoiceoverState {
    data object Idle : VoiceoverState
    data class CountIn(val seconds: Int) : VoiceoverState
    data class Recording(val elapsed: Double, val level: Float) : VoiceoverState
}

data class VoiceoverUi(val state: VoiceoverState = VoiceoverState.Idle, val error: String? = null)

/**
 * A voiceover from the microphone: a count-in of 3-2-1, then AAC in an
 * .m4a in the app's media folder through [MediaRecorder], its level read
 * twenty times a second for the meter; on Stop the file is imported and
 * placed where the playhead stood when recording began.
 */
class VoiceoverViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.concat
    private val _ui = MutableStateFlow(VoiceoverUi())
    val ui: StateFlow<VoiceoverUi> = _ui
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var at = 0.0
    private var session: Job? = null

    fun start(editor: EditorViewModel) {
        if (session?.isActive == true) return
        editor.pause()
        at = editor.ui.value.position
        session = viewModelScope.launch {
            for (n in 3 downTo 1) {
                _ui.update { it.copy(state = VoiceoverState.CountIn(n), error = null) }
                delay(1000)
            }
            val target = File(app.mediaDir, "voiceover-${System.currentTimeMillis()}.m4a")
            val made = try {
                newRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44_100)
                    setAudioEncodingBitRate(128_000)
                    setAudioChannels(1)
                    setOutputFile(target.absolutePath)
                    prepare()
                    start()
                }
            } catch (error: Exception) {
                Log.e(TAG, "the recorder would not start", error)
                _ui.update { VoiceoverUi(error = "The microphone would not start.") }
                return@launch
            }
            recorder = made
            file = target
            val began = SystemClock.elapsedRealtime()
            while (recorder === made) {
                val level = try {
                    made.maxAmplitude / 32767f
                } catch (_: Exception) {
                    0f
                }
                _ui.update { it.copy(state = VoiceoverState.Recording((SystemClock.elapsedRealtime() - began) / 1000.0, level.coerceIn(0f, 1f))) }
                delay(50)
            }
        }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(getApplication<Application>()) else @Suppress("DEPRECATION") MediaRecorder()

    /** Stops and keeps the take: onto the timeline. */
    fun stop(editor: EditorViewModel) {
        val take = finish() ?: return
        editor.placeSound(take.absolutePath, at, "Voiceover")
    }

    /** Stops and throws the take (or the count-in) away. */
    fun discard() {
        finish()?.delete()
    }

    private fun finish(): File? {
        session?.cancel()
        session = null
        val made = recorder
        recorder = null
        val take = file
        file = null
        _ui.update { it.copy(state = VoiceoverState.Idle) }
        if (made == null) return null
        return try {
            made.stop()
            take
        } catch (error: RuntimeException) {
            // Stopped before any sound was written: there is no take.
            Log.w(TAG, "stop with nothing recorded", error)
            take?.delete()
            null
        } finally {
            made.release()
        }
    }

    override fun onCleared() {
        discard()
    }

    private companion object {
        const val TAG = "concat-voiceover"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceoverSheet(voiceover: VoiceoverViewModel, editor: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ui by voiceover.ui.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voiceover.start(editor)
    }
    fun begin() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceover.start(editor)
        } else {
            ask.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val close = {
        voiceover.discard()
        onDismiss()
    }

    ToolPanel("Voiceover", onClose = close, options = ToolPanelOptions(scroll = false, padding = androidx.compose.foundation.layout.PaddingValues(0.dp))) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ui.error?.let { Text(it, color = ConcatColors.warn, fontSize = 12.sp) }
            VoiceoverStateContent(
                ui.state,
                VoiceoverSheetActions(
                    onBegin = ::begin,
                    onDiscard = voiceover::discard,
                    onStop = { voiceover.stop(editor); onDismiss() },
                ),
            )
            Spacer(Modifier.height(Spacing.xs).navigationBarsPadding())
        }
    }
}

private data class VoiceoverSheetActions(
    val onBegin: () -> Unit,
    val onDiscard: () -> Unit,
    val onStop: () -> Unit,
)

@Composable
private fun VoiceoverStateContent(state: VoiceoverState, actions: VoiceoverSheetActions) {
    when (state) {
        VoiceoverState.Idle -> {
            SectionLabel("Records from the microphone and lands at the playhead.")
            Button(
                onClick = actions.onBegin,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = ConcatColors.danger),
                modifier = Modifier.size(84.dp),
            ) { Text("Rec", fontWeight = FontWeight.Bold) }
        }
        is VoiceoverState.CountIn -> {
            Text("${state.seconds}", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            OutlinedButton(onClick = actions.onDiscard) { Text("Cancel") }
        }
        is VoiceoverState.Recording -> {
            val whole = state.elapsed.toLong()
            Text(
                "REC %d:%02d.%d".format(whole / 60, whole % 60, ((state.elapsed - whole) * 10).toInt()),
                color = ConcatColors.danger,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
            )
            LevelMeter(state.level)
            Button(onClick = actions.onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop and place") }
            OutlinedButton(onClick = actions.onDiscard, modifier = Modifier.fillMaxWidth()) { Text("Discard") }
        }
    }
}

/** The microphone's level, as a bar that fills: green, amber near the top, red at it. */
@Composable
private fun LevelMeter(level: Float) {
    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.raised)) {
        // Amplitude is linear; a square root reads more like loudness.
        val shown = kotlin.math.sqrt(level.toDouble()).toFloat().coerceIn(0f, 1f)
        val colour = when {
            shown > 0.92f -> ConcatColors.meterHigh
            shown > 0.75f -> ConcatColors.meterMid
            else -> ConcatColors.meterLow
        }
        Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(colour))
    }
}
