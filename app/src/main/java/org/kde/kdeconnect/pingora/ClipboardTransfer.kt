/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.UUID

/** One manual clipboard send per peer. A receipt confirms the remote clipboard write. */
internal class ClipboardTransfer(private val context: Context, private val bridge: BridgePlugin) {
    private val handler = Handler(Looper.getMainLooper())
    private var completion: ((String) -> Unit)? = null
    private var id = ""
    private var image: ByteArray? = null
    private var offset = 0
    private val timeout = Runnable { finish("Send timed out") }
    val busy get() = completion != null

    fun send(clip: ClipData, sourceRead: () -> Unit, done: (String) -> Unit) {
        if (busy) return
        completion = done
        id = UUID.randomUUID().toString()
        val request = id
        val limit = bridge.clipboardImageLimit
        awaitReceipt()
        Thread({
            val result = runCatching { readPayload(context.contentResolver, clip, limit) }
            handler.post {
                if (id != request) return@post
                sourceRead()
                result.onSuccess { payload ->
                    when (payload) {
                        is ClipboardPayload.Text -> sendText(payload.value)
                        is ClipboardPayload.Image -> sendImage(payload)
                    }
                }.onFailure {
                    finish(when (it) {
                        is ImageTooLarge -> "Image exceeds ${limit / (1024 * 1024)} MiB"
                        is UnsupportedImage -> "Image format unsupported"
                        else -> "Image unavailable"
                    })
                }
            }
        }, "pingora-clipboard").apply { isDaemon = true; start() }
    }

    private fun sendText(text: String) {
        when {
            text.isEmpty() -> finish("Clipboard empty")
            text.toByteArray(Charsets.UTF_8).size > 16384 -> finish("Text too large")
            !bridge.canSend("clipboard") ||
                !bridge.send("clipboard", JSONObject().put("text", text).put("receipt", id)) -> finish("Send failed")
            bridge.supportsClipboardReceipts -> awaitReceipt()
            else -> finish("Clipboard queued")
        }
    }

    private fun sendImage(payload: ClipboardPayload.Image) {
        if (!bridge.supportsClipboardReceipts) {
            finish("Update laptop app")
            return
        }
        image = payload.bytes
        offset = 0
        val header = JSONObject().put("receipt", id).put("mime", payload.mime).put("size", payload.bytes.size)
        if (!bridge.canSend("clipboard") || !bridge.send("clipboard_image", header)) finish("Send failed")
        else awaitReceipt()
    }

    fun receive(data: JSONObject) = handler.post {
        if (!busy || data.optString("receipt") != id) return@post
        when (data.optString("result")) {
            "copied" -> finish("Sent clipboard")
            "next" -> {
                val bytes = image
                if (bytes == null || data.optInt("offset", -1) != offset || offset >= bytes.size ||
                    !bridge.isReady || !bridge.canSend("clipboard")) {
                    finish("Send failed")
                    return@post
                }
                repeat(8) { index ->
                    val start = offset
                    offset = minOf(start + CHUNK, bytes.size)
                    val end = offset == bytes.size
                    if (!bridge.send("clipboard_image_chunk", JSONObject().put("receipt", id)
                            .put("offset", start).put("bytes", Base64.encodeToString(bytes, start, offset - start, Base64.NO_WRAP))
                            .put("ack", index == 7 || end))) {
                        finish("Send failed")
                        return@post
                    }
                    if (end) {
                        awaitReceipt()
                        return@post
                    }
                }
                awaitReceipt()
            }
            else -> finish(when (data.optString("reason")) {
                "locked" -> "Unlock laptop to paste"
                "clipboard_unavailable" -> "Laptop clipboard unavailable"
                else -> "Laptop couldn’t copy"
            })
        }
    }

    fun disconnected() = handler.post { if (busy) finish("Laptop offline") }

    private fun awaitReceipt() {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 10_000)
    }

    private fun finish(label: String) {
        handler.removeCallbacks(timeout)
        image = null
        id = ""
        val done = completion
        completion = null
        done?.invoke(label)
    }

    companion object {
        private const val CHUNK = 12 * 1024
        const val LEGACY_IMAGE_LIMIT = 4 * 1024 * 1024
        const val MAX_IMAGE = 16 * 1024 * 1024
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())

        internal fun readPayload(resolver: ContentResolver, clip: ClipData, limit: Int): ClipboardPayload {
            val text = (0 until clip.itemCount).asSequence()
                .mapNotNull { clip.getItemAt(it).text?.toString() }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            val declaresImage = clip.description.hasMimeType("image/*")
            var failure: Exception? = null
            for (index in 0 until clip.itemCount) {
                val uri = clip.getItemAt(index).uri?.takeIf { it.scheme == "content" } ?: continue
                try {
                    val (mime, bytes) = readImage(resolver, uri, limit)
                    return ClipboardPayload.Image(mime, bytes)
                } catch (error: ImageTooLarge) {
                    throw error
                } catch (error: Exception) {
                    failure = error
                }
            }
            if (declaresImage || failure != null && text.isEmpty()) {
                throw failure ?: FileNotFoundException("Clipboard image has no content URI")
            }
            return ClipboardPayload.Text(text)
        }

        internal fun readImage(resolver: ContentResolver, uri: Uri, limit: Int): Pair<String, ByteArray> {
            require(uri.scheme == "content")
            val raw = try {
                resolver.openInputStream(uri)?.use { readBounded(it, limit) }
            } catch (_: FileNotFoundException) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (raw != null) imageMime(raw)?.let { return it to raw }

            val typed = try {
                resolver.openTypedAssetFileDescriptor(uri, "image/*", null)?.use { descriptor ->
                    descriptor.createInputStream().use { readBounded(it, limit) }
                }
            } catch (_: FileNotFoundException) {
                null
            }
            if (typed != null) imageMime(typed)?.let { return it to typed }
            if (typed != null || raw != null) throw UnsupportedImage()
            throw FileNotFoundException("Clipboard image is unavailable")
        }

        private fun imageMime(bytes: ByteArray): String? = when {
            bytes.matchesAt(PNG_SIGNATURE) -> "image/png"
            bytes.matchesAt(JPEG_SIGNATURE) -> "image/jpeg"
            bytes.matchesAt("RIFF".toByteArray()) && bytes.matchesAt("WEBP".toByteArray(), 8) -> "image/webp"
            bytes.matchesAt("GIF87a".toByteArray()) || bytes.matchesAt("GIF89a".toByteArray()) -> "image/gif"
            else -> null
        }

        private fun ByteArray.matchesAt(signature: ByteArray, offset: Int = 0): Boolean =
            size >= offset + signature.size && signature.indices.all { this[offset + it] == signature[it] }

        private fun readBounded(input: InputStream, limit: Int): ByteArray {
            val buffer = ByteArray(CHUNK)
            val output = ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                if (output.size() + count > limit) throw ImageTooLarge()
                output.write(buffer, 0, count)
            }
        }
    }
}


internal sealed interface ClipboardPayload {
    data class Text(val value: String) : ClipboardPayload
    data class Image(val mime: String, val bytes: ByteArray) : ClipboardPayload
}

private class ImageTooLarge : IllegalArgumentException()
private class UnsupportedImage : IllegalArgumentException()
