/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [23])
class ClipboardLaptopTest {
    @Test
    fun `only the saved paired laptop is eligible even when a VM is online`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val laptop = desktop("laptop", reachable = false)
        val vm = desktop("vm", reachable = true)
        val peers = listOf(vm, laptop)

        assertNull(clipboardLaptop(context, listOf(vm)))
        widgetPreferences(context).edit().putString(CLIPBOARD_LAPTOP_KEY, "laptop").apply()
        assertSame(laptop, clipboardLaptop(context, peers))
        assertNull(clipboardLaptop(context, listOf(vm)))

        every { laptop.isPaired } returns false
        assertNull(clipboardLaptop(context, peers))
    }

    private fun desktop(id: String, reachable: Boolean): Device = mockk {
        every { deviceId } returns id
        every { deviceType } returns DeviceType.DESKTOP
        every { isPaired } returns true
        every { isReachable } returns reachable
    }
}
