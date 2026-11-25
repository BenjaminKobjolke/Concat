// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.projects

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.concat.editor.storage.ProjectFolders
import java.io.File

/** Opens the SAF picker only after Android grants the filesystem access the engine needs. */
@Composable
fun rememberProjectFolderPicker(onPicked: (File) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val folder = ProjectFolders.pathFromTreeUri(context, uri)
            when {
                folder == null -> onError("That folder path is not supported. Choose a folder in shared storage.")
                !folder.isDirectory || !folder.canWrite() -> onError("Concat cannot write to that folder. Choose another folder.")
                else -> onPicked(folder)
            }
        }
    }
    val allFilesAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (ProjectFolders.hasStorageAccess(context)) folderPicker.launch(null)
        else onError("Allow access to all files, then choose the folder again.")
    }
    val legacyStorageAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it } && ProjectFolders.hasStorageAccess(context)) folderPicker.launch(null)
        else onError("Allow storage access, then choose the folder again.")
    }

    return {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !ProjectFolders.hasStorageAccess(context) -> {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
                allFilesAccess.launch(intent)
            }
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !ProjectFolders.hasStorageAccess(context) -> {
                legacyStorageAccess.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                )
            }
            else -> folderPicker.launch(null)
        }
    }
}
