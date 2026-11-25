// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.api.ProjectView
import app.concat.editor.api.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTest {
    @Test(timeout = 30_000)
    fun mainTrackIsLowestTrackWithPictureAndFallsBackToFirstTrack() {
        val view = projectView(
            tracks = listOf(Track("audio", true, false), Track("picture", true, false)),
            clips = listOf(clip("a", "audio", ClipKind.Audio), clip("v", "picture", ClipKind.Video)),
        )

        assertEquals(1, mainTrackIndex(view))
        assertEquals(0, mainTrackIndex(projectView(tracks = view.tracks, clips = emptyList())))
    }

    @Test(timeout = 30_000)
    fun laneLayoutMapsRowsAndDropZones() {
        val layout = LaneLayout(
            rows = listOf(
                LaneRow(1, "top", 20f, 40f, false),
                LaneRow(0, "bottom", 66f, 50f, true),
            ),
            gap = 6f,
        )

        assertEquals(-1, layout.rowAt(10f))
        assertEquals(0, layout.rowAt(30f))
        assertEquals(1, layout.rowAt(72f))
        assertEquals(2, layout.rowAt(141f))
        assertEquals(1, layout.rowOfTrack("bottom"))
    }

    private fun projectView(tracks: List<Track>, clips: List<Clip>) = ProjectView(
        name = "Timeline test",
        width = 1920,
        height = 1080,
        rateNum = 30,
        rateDen = 1,
        canUndo = false,
        canRedo = false,
        media = emptyList(),
        tracks = tracks,
        clips = clips,
        createdId = null,
    )

    private fun clip(id: String, trackId: String, kind: ClipKind) = Clip(
        id = id,
        trackId = trackId,
        mediaId = "media-$id",
        name = id,
        kind = kind,
        start = 0.0,
        duration = 1.0,
        sourceStart = 0.0,
        volume = 1.0,
        speed = 1.0,
        text = null,
    )
}
