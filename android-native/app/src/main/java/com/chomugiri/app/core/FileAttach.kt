package com.chomugiri.app.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Text-only attach. There is no vision/multimodal call anywhere in LlmClient, so an attached
 * photo would just be a file the model can't actually see — reading a photo's bytes as UTF-8
 * would silently produce garbage. Only text/code files are supported until real image input is
 * wired up.
 */
fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }

/** Reads up to [maxBytes] as UTF-8 text; returns null if the file isn't readable as text. */
fun readTextFile(context: Context, uri: Uri, maxBytes: Int): String? =
    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes().take(maxBytes).toByteArray()
            val text = bytes.toString(Charsets.UTF_8)
            // A high rate of replacement characters means this almost certainly wasn't text.
            val bad = text.count { it == '�' }
            if (text.isNotEmpty() && bad.toDouble() / text.length > 0.02) null else text
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
