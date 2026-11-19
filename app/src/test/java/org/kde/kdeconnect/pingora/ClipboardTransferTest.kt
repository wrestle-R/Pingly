/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.content.ContentResolver
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

        val (mime, bytes) = ClipboardTransfer.readImage(resolver, uri)

        assertEquals("image/png", mime)
        assertArrayEquals(png, bytes)
    }
}
