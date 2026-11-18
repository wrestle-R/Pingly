/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.kde.kdeconnect.Device

class WidgetDevicesTest {
    @Test
    fun `reachable devices keep their rows when a saved duplicate is offline`() {
        val savedLaptop = device("archlinux", reachable = false)
        val liveLaptop = device("archlinux", reachable = true)
        val liveVm = device("InstaBox", reachable = true)
        val offlineDesktop = device("Desktop", reachable = false)

        val visible = listOf(savedLaptop, liveLaptop, liveVm, offlineDesktop).withoutOfflineDuplicates()

        assertEquals(listOf(liveLaptop, liveVm, offlineDesktop), visible)
    }

    private fun device(displayName: String, reachable: Boolean): Device = mockk {
        every { name } returns displayName
        every { isReachable } returns reachable
    }
}
