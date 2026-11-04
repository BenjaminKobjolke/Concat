package app.concat.editor.spike

import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.concat_mobile.Engine
import uniffi.concat_mobile.Stats
import uniffi.concat_mobile.VideoInfo

internal data class EditorSession(
    val engine: Engine,
    val importPath: String?,
    val bench: Boolean,
    val initialMode: String,
    val copyIn: (android.net.Uri) -> File,
)

internal suspend fun importVideo(engine: Engine, file: File): Pair<Boolean, String> {
    val started = SystemClock.elapsedRealtime()
    return try {
        withContext(Dispatchers.IO) { engine.importVideo(file.absolutePath) }
        true to "imported ${file.name} in ${SystemClock.elapsedRealtime() - started} ms"
    } catch (error: Exception) {
        false to "import failed: ${error.message}"
    }
}

@Composable
internal fun EditorImportButton(session: EditorSession, autoImport: Boolean, onImported: () -> Unit, onMessage: (String) -> Unit) {
    val engine = session.engine
    val scope = rememberCoroutineScope()
    suspend fun importFile(file: File) {
        val (imported, result) = importVideo(engine, file)
        if (imported) onImported()
        onMessage(result)
        Log.i(TAG, result)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val started = SystemClock.elapsedRealtime()
            val file = withContext(Dispatchers.IO) { session.copyIn(uri) }
            Log.i(TAG, "copied ${file.name} (${file.length() / 1024} KB) in ${SystemClock.elapsedRealtime() - started} ms")
            importFile(file)
        }
    }
    LaunchedEffect(session.importPath, session.bench) {
        if (session.importPath != null && autoImport) importFile(File(session.importPath))
        if (session.bench) runBench(engine)
    }
    Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
        Text("Import")
    }
}

@Composable
internal fun ColumnScope.EditorSurface(engine: Engine, video: VideoInfo, mode: String, modifier: Modifier, onMessage: (String) -> Unit) {
    val aspect = if (video.height > 0u) video.width.toFloat() / video.height.toFloat() else 9f / 16f
    Box(modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        // Replace the SurfaceView when the renderer changes; GPU windows cannot be locked by the CPU path.
        key(mode) {
            AndroidView(
                modifier = Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f),
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = Unit
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                val window = ConcatNative.windowFromSurface(holder.surface)
                                if (window == 0L) return
                                val started = SystemClock.elapsedRealtime()
                                try {
                                    val text = engine.attachSurface(window.toULong(), width.toUInt(), height.toUInt(), mode)
                                    Log.i("concat-spike-ui", "attached ${width}x$height in ${SystemClock.elapsedRealtime() - started} ms: $text")
                                } catch (error: Exception) {
                                    onMessage("surface: ${error.message}")
                                    Log.e("concat-spike-ui", "surface: ${error.message}")
                                }
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) = engine.detachSurface()
                        })
                    }
                },
            )
        }
    }
}

@Composable
internal fun EditorTimeline(clips: List<uniffi.concat_mobile.ClipView>, position: Double, duration: Double) {
    val perSecond = 48.dp
    Box(Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF1C1C1C)).horizontalScroll(rememberScrollState())) {
        Box(Modifier.width(perSecond * maxOf(duration.toFloat(), 1f)).fillMaxHeight()) {
            for (clip in clips) {
                Box(
                    Modifier.offset(x = perSecond * clip.start.toFloat(), y = (clip.lane.toInt() * 4).dp)
                        .width(perSecond * clip.duration.toFloat()).height(48.dp).padding(horizontal = 1.dp)
                        .background(Color(0xFF2F6FDE)).border(1.dp, Color(0xFF9CC0FF)),
                ) {
                    Text(clip.name, color = Color.White, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp))
                }
            }
            Spacer(Modifier.offset(x = perSecond * position.toFloat()).width(2.dp).fillMaxHeight().background(Color.White))
        }
    }
}

@Composable
internal fun EditorMode(mode: String, onMode: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in listOf("vulkan", "gl", "cpu")) {
            FilterChip(selected = mode == option, onClick = { onMode(option) }, label = { Text(option) })
        }
    }
}

@Composable
internal fun EditorStats(stats: Stats, message: String) {
    Text(
        buildString {
            append(stats.renderer.ifEmpty { "no surface" })
            append("\nplay ${"%.1f".format(stats.playFps)} fps, late ${stats.lateFrames}; ")
            append("decode ${"%.1f".format(stats.lastDecodeMs)} ms, draw ${"%.1f".format(stats.lastDrawMs)} ms")
            append("\nscrub last ${"%.0f".format(stats.lastScrubMs)} / mean ${"%.0f".format(stats.meanScrubMs)} / max ${"%.0f".format(stats.maxScrubMs)} ms over ${stats.scrubs}")
            if (stats.lastError.isNotEmpty()) append("\n${stats.lastError}")
            if (message.isNotEmpty()) append("\n$message")
        },
        color = Color(0xFFBBBBBB), fontSize = 11.sp, lineHeight = 13.sp,
    )
}
