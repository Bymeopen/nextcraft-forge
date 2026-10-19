package com.nextcraft.forge.runtime

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nextcraft.forge.model.ChatAttachment
import java.io.File
import java.util.UUID

/** Copies picked documents into the workspace so the agent (inside the chroot) can read them. */
object AttachmentStore {
    const val RELATIVE_DIR = ".nextcraft/attachments"
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_PER_PROMPT = 10

    fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return (fromProvider ?: uri.lastPathSegment ?: "attachment").substringAfterLast('/')
    }

    /** Returns null when the document exceeds [MAX_BYTES] or cannot be read. */
    fun copyInto(context: Context, workspace: File, uri: Uri): ChatAttachment? {
        val name = sanitize(displayName(context, uri))
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val dir = File(workspace, RELATIVE_DIR).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id-$name")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_BYTES) return@use -1L
                        output.write(buffer, 0, count)
                    }
                    total
                }
            } ?: -1L
        }.getOrDefault(-1L)
        if (copied < 0L) {
            target.delete()
            return null
        }
        return ChatAttachment(
            id = id,
            displayName = name,
            relativePath = "$RELATIVE_DIR/${target.name}",
            mimeType = mime,
            sizeBytes = copied,
        )
    }

    fun file(workspace: File, attachment: ChatAttachment): File = File(workspace, attachment.relativePath)

    fun delete(workspace: File, attachment: ChatAttachment) {
        runCatching { file(workspace, attachment).delete() }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").trim('.', '_').ifBlank { "file" }.take(80)
}
