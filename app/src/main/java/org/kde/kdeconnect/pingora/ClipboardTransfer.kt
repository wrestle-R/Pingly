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

    fun send(item: ClipData.Item, isImage: Boolean, sourceRead: () -> Unit, done: (String) -> Unit) {
        if (busy) return
        completion = done
        id = UUID.randomUUID().toString()
        val request = id
        val uri = item.uri
        val text = item.text?.toString()
        if (isImage && uri == null) {
            finish("Image unavailable")
            return
        }
        if (uri == null || !isImage && text != null) {
            sourceRead()
            when {
                text.isNullOrEmpty() -> finish("Clipboard empty")
                text.toByteArray(Charsets.UTF_8).size > 16384 -> finish("Text too large")
                !bridge.send("clipboard", JSONObject().put("text", text).put("receipt", id)) -> finish("Send failed")
                bridge.supportsClipboardReceipts -> awaitReceipt()
                else -> finish("Clipboard queued")
            }
            return
        }
        if (!bridge.supportsClipboardReceipts) {
            finish("Update laptop app")
            return
        }
        awaitReceipt()
        Thread({
            val result = runCatching {
                readImage(context.contentResolver, uri)
            }
            handler.post {
                if (id != request) return@post
                sourceRead()
                result.onSuccess { (mime, bytes) ->
                    image = bytes
                    offset = 0
                    if (!bridge.canSend("clipboard") || !bridge.send("clipboard_image", JSONObject()
                            .put("receipt", id).put("mime", mime).put("size", bytes.size))) finish("Send failed")
                    else awaitReceipt()
                }.onFailure {
                    finish(when (it.message) {
                        "Image exceeds 4 MiB" -> "Image exceeds 4 MiB"
                        "Unsupported clipboard image" -> "Image format unsupported"
                        else -> "Image unavailable"
                    })
                }
            }
        }, "pingora-clipboard-image").apply { isDaemon = true; start() }
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
            else -> finish("Laptop couldn’t copy")
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
        private const val MAX_IMAGE = 4 * 1024 * 1024
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())

        internal fun readImage(resolver: ContentResolver, uri: Uri): Pair<String, ByteArray> {
            require(uri.scheme == "content")
            val input = try {
                resolver.openInputStream(uri)
            } catch (_: FileNotFoundException) {
                null
            }
            val bytes = input?.use(::readBounded) ?: resolver.openTypedAssetFileDescriptor(uri, "image/*", null)
                ?.use { descriptor -> descriptor.createInputStream().use(::readBounded) }
                ?: error("Image unavailable")
            val mime = when {
                bytes.matchesAt(PNG_SIGNATURE) -> "image/png"
                bytes.matchesAt(JPEG_SIGNATURE) -> "image/jpeg"
                bytes.matchesAt("RIFF".toByteArray()) && bytes.matchesAt("WEBP".toByteArray(), 8) -> "image/webp"
                bytes.matchesAt("GIF87a".toByteArray()) || bytes.matchesAt("GIF89a".toByteArray()) -> "image/gif"
                else -> error("Unsupported clipboard image")
            }
            return mime to bytes
        }

        private fun ByteArray.matchesAt(signature: ByteArray, offset: Int = 0): Boolean =
            size >= offset + signature.size && signature.indices.all { this[offset + it] == signature[it] }

        private fun readBounded(input: InputStream): ByteArray {
            val buffer = ByteArray(CHUNK)
            val output = ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                check(output.size() + count <= MAX_IMAGE) { "Image exceeds 4 MiB" }
                output.write(buffer, 0, count)
            }
        }
    }
}
