// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectFoldersTest {
    @Test
    fun primaryVolumeMapsToSharedStorageRoot() {
        assertEquals(
            "/storage/emulated/0/Movies/Concat",
            ProjectFolders.documentIdToPath("primary:Movies/Concat", "/storage/emulated/0", emptySet()),
        )
    }

    @Test
    fun primaryVolumeRootMapsToSharedStorageRoot() {
        assertEquals(
            "/storage/emulated/0",
            ProjectFolders.documentIdToPath("primary:", "/storage/emulated/0", emptySet()),
        )
    }

    @Test
    fun sdCardVolumeMapsToItsStorageRoot() {
        assertEquals(
            "/storage/ABCD-1234/Concat",
            ProjectFolders.documentIdToPath("ABCD-1234:Concat", "/storage/emulated/0", setOf("ABCD-1234")),
        )
    }

    @Test
    fun unknownOrUnsupportedDocumentIdsAreRejected() {
        listOf("home:Concat", "raw:/storage/emulated/0/Concat", "Concat").forEach { documentId ->
            assertNull(ProjectFolders.documentIdToPath(documentId, "/storage/emulated/0", setOf("ABCD-1234")))
        }
    }

    @Test
    fun pathTraversalSegmentsAreRejected() {
        listOf("primary:.", "primary:Concat/..", "ABCD-1234:../Concat").forEach { documentId ->
            assertNull(ProjectFolders.documentIdToPath(documentId, "/storage/emulated/0", setOf("ABCD-1234")))
        }
    }
}
