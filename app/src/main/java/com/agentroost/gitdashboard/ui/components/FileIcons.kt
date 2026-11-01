package com.agentroost.gitdashboard.ui.components

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Folder and file glyphs used by the browser.
 *
 * `material-icons-core` does not ship `Folder`/`InsertDriveFile`, and pulling in
 * `material-icons-extended` (several MB of generated vectors) for two icons is not worth it, so the
 * two shapes are declared locally on the standard 24dp Material grid.
 */
val FolderIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Folder") {
        materialPath {
            moveTo(3f, 6f)
            lineTo(9f, 6f)
            lineTo(11f, 8f)
            lineTo(21f, 8f)
            lineTo(21f, 19f)
            lineTo(3f, 19f)
            close()
        }
    }
}

val FileIcon: ImageVector by lazy {
    materialIcon(name = "Filled.InsertDriveFile") {
        materialPath {
            moveTo(6f, 3f)
            lineTo(14f, 3f)
            lineTo(19f, 8f)
            lineTo(19f, 21f)
            lineTo(6f, 21f)
            close()
        }
    }
}
