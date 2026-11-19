/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [23])
class ResumeWidgetTest {
    @Test
    fun `a refresh tapped during reconnect checks the relay once it is ready`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sent = mutableListOf<String>()
        var ready = false
        val bridge = mockk<BridgePlugin> {
            every { peerId } returns "relay"
            every { canResumeInstabox } answers { ready }
            every { canCheckInstabox } answers { ready }
            every { send(any(), any()) } answers {
                sent += firstArg<String>()
                true
            }
        }
        val peer = mockk<Device> {
            every { isPaired } returns true
            every { isReachable } returns true
            every { getPlugin(BridgePlugin::class.java) } returns bridge
        }
        val application = mockk<KdeConnect> {
            every { devices } returns ConcurrentHashMap(mapOf("relay" to peer))
        }
        val widget = ResumeWidget()
        val looper = shadowOf(Looper.getMainLooper())
        mockkObject(KdeConnect.Companion, BackgroundService.Companion)
        try {
            every { KdeConnect.getInstance() } returns application
            every { BackgroundService.ForceRefreshConnections(any()) } returns Unit
            widget.onReceive(app, Intent("io.github.wrestle_r.pingora.INSTABOX_STATUS"))
            assertEquals(emptyList<String>(), sent)

            ready = true
            looper.idleFor(2, TimeUnit.SECONDS)
            assertEquals(listOf("instabox_status"), sent)
        } finally {
            widget.onDisabled(app)
            unmockkObject(KdeConnect.Companion, BackgroundService.Companion)
        }
    }

    @Test
    fun `accepted wake keeps checking through an unknown status and stops when online`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sent = mutableListOf<Pair<String, String>>()
        val bridge = mockk<BridgePlugin> {
            every { peerId } returns "relay"
            every { canResumeInstabox } returns true
            every { canCheckInstabox } returns true
            every { send(any(), any()) } answers {
                sent += firstArg<String>() to secondArg<JSONObject>().getString("receipt")
                true
            }
        }
        val peer = mockk<Device> {
            every { isPaired } returns true
            every { isReachable } returns true
            every { getPlugin(BridgePlugin::class.java) } returns bridge
        }
        val application = mockk<KdeConnect> {
            every { devices } returns ConcurrentHashMap(mapOf("relay" to peer))
        }
        val widget = ResumeWidget()
        val looper = shadowOf(Looper.getMainLooper())
        mockkObject(KdeConnect.Companion)
        try {
            every { KdeConnect.getInstance() } returns application
            widget.onReceive(app, Intent("io.github.wrestle_r.pingora.INSTABOX_RESUME"))
            assertEquals(listOf("instabox_resume"), sent.map { it.first })

            fun reply(state: String) {
                ResumeWidget.result(app, "relay", JSONObject().put("receipt", sent.last().second).put("state", state))
                looper.idle()
            }

            reply("requested")
            looper.idleFor(10, TimeUnit.SECONDS)
            assertEquals(listOf("instabox_resume", "instabox_status"), sent.map { it.first })

            reply("unknown")
            looper.idleFor(10, TimeUnit.SECONDS)
            assertEquals(listOf("instabox_resume", "instabox_status", "instabox_status"), sent.map { it.first })

            reply("running")
            looper.idleFor(90, TimeUnit.SECONDS)
            assertEquals(3, sent.size)
        } finally {
            widget.onDisabled(app)
            unmockkObject(KdeConnect.Companion)
        }
    }
}
