// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor

import android.app.Application
import android.content.Context
import app.concat.editor.api.ConcatApi
import app.concat.editor.engine.EngineBridge
import app.concat.editor.engine.EngineFactory
import app.concat.editor.export.ExportManager
import app.concat.editor.media.MediaCache
import app.concat.editor.models.ModelStore
import app.concat.editor.storage.ProjectFolders
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** One editor per process: the API, and the export that outlives any screen. */
class ConcatApplication : Application() {
    /** Work that belongs to the process, not to a screen: an export, a save on the way out. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val engine: Result<EngineBridge> by lazy { runCatching { EngineFactory.create(this) } }

    /** Why the engine could not start, or null. A release build shows this and nothing else. */
    val engineFailure: Throwable? get() = engine.exceptionOrNull()

    val api: ConcatApi by lazy { ConcatApi(engine.getOrThrow()) }
    val exports: ExportManager by lazy { ExportManager(this, api, scope) }
    val models: ModelStore by lazy { ModelStore(api, scope) }
    /** The timeline's filmstrips and waveforms, cached in memory. */
    val media: MediaCache by lazy { MediaCache(api, scope) }

    /**
     * Where new projects are made. A user-selected shared folder lets a sync
     * client copy projects from a desktop; otherwise use Concat's app folder.
     */
    val appProjectsDir: File get() = File(getExternalFilesDir(null) ?: filesDir, "Concat").apply { mkdirs() }
    val projectsDir: File get() = ProjectFolders.selectedProjectDirectory(this) ?: appProjectsDir

    /** Where picked files are copied: the engine reads paths, not content URIs. */
    val mediaDir: File get() = File(filesDir, "media").apply { mkdirs() }
}

val Context.concat: ConcatApplication get() = applicationContext as ConcatApplication
