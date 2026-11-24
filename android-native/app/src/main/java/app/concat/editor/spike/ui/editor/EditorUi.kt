// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import android.graphics.Bitmap
import app.concat.editor.spike.api.ClipBounds
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.api.ShapeCard

data class EditorUi(
    val view: ProjectView? = null,
    val selected: String? = null,
    val position: Double = 0.0,
    val playing: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    /** The clip a reverse is being written for, and how far it is (0..1). */
    val reversing: Pair<String, Float>? = null,
    /** Each title's and shape's block as the engine painted it, for the transform box. */
    val bounds: Map<String, ClipBounds> = emptyMap(),
    /** The Stickers page's cards, once the engine has said them. */
    val stickers: List<ShapeCard> = emptyList(),
    /** The cover the project names, painted; none while it names none (the timeline shows its first frame). */
    val cover: Bitmap? = null,
)
