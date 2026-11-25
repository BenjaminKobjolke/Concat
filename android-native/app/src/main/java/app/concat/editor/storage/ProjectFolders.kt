// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.storage.StorageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

/** Converts Android's folder-picker result to the real project paths the shared engine reads. */
object ProjectFolders {
    private const val PREFERENCES = "project_folders"
    private const val PROJECTS_FOLDER = "projects_folder"
    private const val TAG = "project-folders"

    /** Returns the selected folder only while broad storage access remains usable. */
    fun selectedProjectDirectory(context: Context): File? {
        val path = preferences(context).getString(PROJECTS_FOLDER, null) ?: return null
        if (!hasStorageAccess(context)) return null
        return File(path).takeIf { it.isDirectory && it.canWrite() }
    }

    fun saveSelectedProjectDirectory(context: Context, directory: File) {
        preferences(context).edit().putString(PROJECTS_FOLDER, directory.absolutePath).apply()
    }

    fun clearSelectedProjectDirectory(context: Context) {
        preferences(context).edit().remove(PROJECTS_FOLDER).apply()
    }

    fun hasStorageAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** The SAF document id contains a volume and a relative path, not a content URI path. */
    fun pathFromTreeUri(context: Context, uri: Uri): File? = try {
        @Suppress("DEPRECATION")
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        val volumeIds = context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.uuid }.toSet()
        documentIdToPath(DocumentsContract.getTreeDocumentId(uri), primaryRoot, volumeIds)?.let(::File)
    } catch (error: RuntimeException) {
        Log.w(TAG, "Could not resolve the selected project folder", error)
        null
    }

    /** Pure conversion kept separate from Android so primary, removable and hostile ids are testable. */
    internal fun documentIdToPath(documentId: String, primaryRoot: String, volumeIds: Set<String>): String? {
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null

        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1)
        if (relative.startsWith('/')) return null
        val parts = relative.split('/').filter(String::isNotEmpty)
        if (parts.any { it == "." || it == ".." || '\\' in it }) return null

        val root = when {
            volume.equals("primary", ignoreCase = true) -> primaryRoot
            volume.matches(Regex("[A-Za-z0-9_-]+")) && volume in volumeIds -> "/storage/$volume"
            else -> return null
        }
        return if (parts.isEmpty()) root else "${root.trimEnd('/')}/${parts.joinToString("/")}"
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
