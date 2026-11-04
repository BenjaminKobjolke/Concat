package app.concat.editor.spike

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.delay
import uniffi.concat_mobile.Engine
import uniffi.concat_mobile.Stats

internal const val TAG = "concat-spike-ui"

class MainActivity : ComponentActivity() {
    private lateinit var engine: Engine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val started = SystemClock.elapsedRealtime()
        ConcatNative.init(applicationContext)
        val loaded = SystemClock.elapsedRealtime()
        engine = Engine(filesDir.absolutePath)
        val opened = SystemClock.elapsedRealtime()
        Log.i(TAG, "library loaded in ${loaded - started} ms, engine open in ${opened - loaded} ms")
        // Made by the app, so a file adb pushes into it is the app's to read.
        Log.i(TAG, "push test media to ${getExternalFilesDir(null)}")
        // For a run driven over adb: --es import <path> [--ez bench true] [--es mode vulkan|gl|cpu]
        val importPath = intent.getStringExtra("import")
        val bench = intent.getBooleanExtra("bench", false)
        val mode = intent.getStringExtra("mode") ?: "vulkan"
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Editor(EditorSession(engine, importPath, bench, mode, ::copyIn))
            }
        }
    }

    /** A picked document copied into the app's own storage: the engine reads paths. */
    private fun copyIn(uri: Uri): File {
        var name = "picked.mp4"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val media = File(filesDir, "media").apply { mkdirs() }
        val target = File(media, name)
        contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it, 1 shl 20) }
        }
        return target
    }
}

@Composable
private fun Editor(session: EditorSession) {
    val engine = session.engine
    var clips by remember { mutableStateOf(engine.clips()) }
    var duration by remember { mutableStateOf(engine.duration()) }
    var video by remember { mutableStateOf(engine.video()) }
    var position by remember { mutableStateOf(0.0) }
    var playing by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(Stats("", 0u, 0.0, 0.0, 0.0, 0u, 0.0, 0.0, 0.0, 0u, 0.0, "")) }
    var mode by remember { mutableStateOf(session.initialMode) }
    var message by remember { mutableStateOf("") }
    var scrubbing by remember { mutableStateOf(false) }

    fun refresh() {
        clips = engine.clips()
        duration = engine.duration()
        video = engine.video()
    }

    // The playhead and the numbers, read from the engine at the display's pace.
    LaunchedEffect(Unit) {
        while (true) {
            if (!scrubbing) position = engine.position()
            playing = engine.isPlaying()
            stats = engine.stats()
            delay(33)
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF111111)).safeDrawingPadding().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EditorSurface(engine, video, mode, Modifier.weight(1f)) { message = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorImportButton(session, clips.isEmpty(), ::refresh) { message = it }
            Button(onClick = { if (playing) engine.pause() else engine.play() }, enabled = duration > 0) {
                Text(if (playing) "Pause" else "Play")
            }
            Text("${"%.2f".format(position)} / ${"%.2f".format(duration)} s", color = Color.White)
        }

        Slider(
            value = position.toFloat(),
            valueRange = 0f..maxOf(duration.toFloat(), 0.01f),
            onValueChange = {
                scrubbing = true
                position = it.toDouble()
                engine.seek(it.toDouble())
            },
            onValueChangeFinished = { scrubbing = false },
            enabled = duration > 0,
        )

        EditorTimeline(clips, position, duration)
        EditorMode(mode) { mode = it }
        EditorStats(stats, message)
    }
}

/** A scripted run, for numbers without a finger: jumps, a drag, then playback. */
internal suspend fun runBench(engine: Engine) {
    delay(1500)
    val duration = engine.duration()
    if (duration <= 0) {
        Log.w("concat-spike-bench", "nothing on the timeline")
        return
    }
    fun report(label: String) {
        val s = engine.stats()
        Log.i(
            "concat-spike-bench",
            "$label: renderer='${s.renderer}' frames=${s.frames} scrubs=${s.scrubs} " +
                "scrub last=${"%.1f".format(s.lastScrubMs)} mean=${"%.1f".format(s.meanScrubMs)} max=${"%.1f".format(s.maxScrubMs)} ms " +
                "decode=${"%.1f".format(s.lastDecodeMs)} draw=${"%.1f".format(s.lastDrawMs)} ms " +
                "playFps=${"%.1f".format(s.playFps)} late=${s.lateFrames} audioPeak=${"%.3f".format(s.audioPeak)} error='${s.lastError}'",
        )
    }
    // Jumps: each seek waited out, the way a tap on the strip lands.
    engine.resetStats()
    delay(200)
    for (i in 0 until 30) {
        engine.seek(duration * ((i * 7) % 30) / 30.0)
        delay(250)
    }
    report("jumps")
    // A drag: a seek every 16 ms, as a finger on the slider sends them.
    engine.resetStats()
    delay(200)
    var at = 0.0
    while (at < minOf(duration, 4.0)) {
        engine.seek(at)
        at += 0.05
        delay(16)
    }
    delay(600)
    report("drag")
    // Playback for six seconds from the start.
    engine.seek(0.0)
    delay(300)
    engine.resetStats()
    delay(100)
    engine.play()
    delay(6000)
    report("play")
    engine.pause()
}
