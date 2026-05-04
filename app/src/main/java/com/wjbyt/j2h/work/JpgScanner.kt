package com.wjbyt.j2h.work

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object JpgScanner {

    data class Found(val file: DocumentFile, val parent: DocumentFile)

    /** Recursively walk a tree URI, yielding every JPG with its containing directory. */
    fun scan(context: Context, treeUri: Uri): List<Found> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<Found>()
        walk(root, out)
        return out
    }

    private fun walk(dir: DocumentFile, out: MutableList<Found>) {
        val children = try { dir.listFiles() } catch (e: Exception) { return }
        for (child in children) {
            if (child.isDirectory) {
                walk(child, out)
            } else if (child.isFile) {
                val name = child.name?.lowercase() ?: continue
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".dng")) {
                    out += Found(child, dir)
                }
            }
        }
    }
}
