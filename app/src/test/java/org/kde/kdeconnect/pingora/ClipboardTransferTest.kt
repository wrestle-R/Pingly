/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.content.ClipData
import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [23])
class ClipboardTransferTest {
    @Test
    fun `copied screenshot with generic provider type preserves PNG bytes`() {
        val uri = Uri.parse("content://clipboard.provider/copied-screenshot")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00)
        val resolver = mockk<ContentResolver> {
            every { getType(uri) } returns "application/octet-stream"
            every { openInputStream(uri) } returns ByteArrayInputStream(png)
        }

        val (mime, bytes) = ClipboardTransfer.readImage(resolver, uri, ClipboardTransfer.LEGACY_IMAGE_LIMIT)

        assertEquals("image/png", mime)
        assertArrayEquals(png, bytes)
    }
    @Test
    fun `copied image with a text fallback and generic MIME sends the image`() {
        val uri = Uri.parse("content://clipboard.provider/copied-image")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val descriptor = mockk<AssetFileDescriptor>(relaxed = true) {
            every { createInputStream() } returns mockk(relaxed = true) {
                every { read(any<ByteArray>()) } answers {
                    png.copyInto(firstArg())
                    png.size
                } andThen -1
            }
        }
        val resolver = mockk<ContentResolver> {
            every { openInputStream(uri) } returns ByteArrayInputStream("metadata".toByteArray())
            every { openTypedAssetFileDescriptor(uri, "image/*", null) } returns descriptor
        }
        val clip = ClipData("Image", arrayOf("application/octet-stream"), ClipData.Item("Preview", null, uri))

        val (mime, bytes) = ClipboardTransfer.readPayload(resolver, clip, ClipboardTransfer.LEGACY_IMAGE_LIMIT) as ClipboardPayload.Image

        assertEquals("image/png", mime)
        assertArrayEquals(png, bytes)
    }

    @Test
    fun `large screenshot uses the receiving laptop limit`() {
        val uri = Uri.parse("content://clipboard.provider/large-screenshot")
        val png = ByteArray(5 * 1024 * 1024)
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(png)
        val resolver = mockk<ContentResolver> {
            every { openInputStream(uri) } answers { ByteArrayInputStream(png) }
        }
        val clip = ClipData("Screenshot", arrayOf("image/png"), ClipData.Item(uri))

        val (_, bytes) = ClipboardTransfer.readPayload(resolver, clip, ClipboardTransfer.MAX_IMAGE) as ClipboardPayload.Image

        assertArrayEquals(png, bytes)
    }

}
