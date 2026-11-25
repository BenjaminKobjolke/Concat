// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.Surface
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

/** A clock and a placeholder picture: the clip under the playhead, named, on its colour. */
internal class FakePreview(private val bridge: FakeEngineBridge, private val path: String) : PreviewBridge {
    @Volatile private var view: JSONObject? = bridge.snapshot(path)
    @Volatile private var surface: Surface? = null
    @Volatile private var playing = false
    @Volatile private var anchor = 0.0
    @Volatile private var anchorAt = 0L
    private var painter: Thread? = null

    override fun attachSurface(surface: Surface, width: Int, height: Int, mode: String) {
        detachSurface()
        this.surface = surface
        painter = thread(name = "fake-preview") {
            while (this.surface === surface) {
                paint(surface)
                Thread.sleep(33)
            }
        }
    }

    override fun detachSurface() {
        surface = null
        painter?.join(500)
        painter = null
    }

    override fun refresh() {
        view = bridge.snapshot(path)
        if (position() > duration()) seek(duration())
    }

    override fun play() {
        if (position() >= duration() - 0.01) anchor = 0.0 else anchor = position()
        anchorAt = SystemClock.elapsedRealtimeNanos()
        playing = duration() > 0
    }

    override fun pause() {
        anchor = position()
        playing = false
    }

    override fun seek(seconds: Double) {
        anchor = seconds.coerceIn(0.0, max(0.0, duration()))
        anchorAt = SystemClock.elapsedRealtimeNanos()
    }

    override fun position(): Double {
        if (!playing) return anchor
        val at = anchor + (SystemClock.elapsedRealtimeNanos() - anchorAt) / 1e9
        if (at >= duration()) {
            playing = false
            anchor = duration()
        }
        return min(at, duration())
    }

    override fun duration(): Double {
        val clips = view?.optJSONObject("project")?.optJSONArray("timelines")?.optJSONObject(0)?.optJSONArray("clips") ?: return 0.0
        return clips.objectsList().maxOfOrNull { it.optDouble("start") + it.optDouble("duration") } ?: 0.0
    }

    override fun isPlaying() = playing && position() < duration()

    override fun release() = detachSurface()

    private fun paint(surface: Surface) {
        val canvas: Canvas = try {
            surface.lockCanvas(null)
        } catch (_: Exception) {
            return
        }
        try {
            FakeFrame.paint(canvas, view, position(), showClock = true)
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
            }
        }
    }
}


