/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
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
    fun `relay reconnection refreshes status after a transport failure without replaying wake`() =
        withInstalledWidget {
            for (wake in listOf(false, true)) {
                for (failure in RequestFailure.entries) {
                    reset()
                    reachable = failure != RequestFailure.OFFLINE
                    enqueueSucceeded = failure != RequestFailure.ENQUEUE
                    tap(wake)
                    val settleAfter = when (failure) {
                        RequestFailure.OFFLINE -> 10_001L
                        RequestFailure.ENQUEUE -> 0L
                        RequestFailure.NO_REPLY -> if (wake) 65_001L else 15_001L
                    }
                    advance(settleAfter)
                    val initial = if (reachable) listOf(kind(wake)) else emptyList()
                    assertEquals("$wake/$failure before reconnect", initial, sent.map { it.kind })

                    reachable = true
                    enqueueSucceeded = true
                    policyArrived()
                    policyArrived()
                    reply("running")
                    policyArrived()
                    assertEquals("$wake/$failure after reconnect", initial + "instabox_status", sent.map { it.kind })
                }
            }
        }

    @Test
    fun `a surviving relay session gets one read-only retry after a request goes unanswered`() =
        withInstalledWidget {
            for (wake in listOf(false, true)) {
                reset()
                tap(wake)
                advance(if (wake) 65_001L else 15_001L)
                assertEquals(listOf(kind(wake)), sent.map { it.kind })

                advance(10_000)
                assertEquals(listOf(kind(wake), "instabox_status"), sent.map { it.kind })
                advance(90_000)
                assertEquals(listOf(kind(wake), "instabox_status"), sent.map { it.kind })
            }
        }

    @Test
    fun `accepted wake still checks for startup when the relay drops before processing its receipt`() = withRelay {
        tap(wake = true)
        assertEquals(listOf("instabox_resume"), sent.map { it.kind })

        reachable = false
        reply("requested")
        advance(9_000)
        assertEquals(1, sent.size)

        reachable = true
        advance(8_000)
        assertEquals(listOf("instabox_resume", "instabox_status"), sent.map { it.kind })
        reply("running")
        advance(90_000)
        assertEquals(2, sent.size)
    }

    @Test
    fun `reconnect matrix only sends once to a unique reachable and authorized relay`() = withRelay {
        for (wake in listOf(false, true)) {
            for (obstacle in RelayObstacle.entries) {
                for (delay in listOf(0L, 1L, 249L, 250L, 1_250L, 9_749L, 10_001L)) {
                    reset()
                    when (obstacle) {
                        RelayObstacle.DISCONNECTED -> reachable = false
                        RelayObstacle.UNPAIRED -> paired = false
                        RelayObstacle.NO_PERMISSION -> authorized = false
                        RelayObstacle.TWO_RELAYS -> peers["other"] = secondPeer
                        RelayObstacle.NO_DISCOVERY -> peers.remove("relay")
                    }
                    tap(wake)
                    advance(delay)
                    assertEquals("$wake/$obstacle/$delay dispatched before availability", 0, sent.size)

                    peers["relay"] = peer
                    peers.remove("other")
                    paired = true
                    authorized = true
                    reachable = true
                    advance(11_000)
                    val expected = if (delay < 10_000) listOf(kind(wake)) else emptyList()
                    assertEquals("$wake/$obstacle/$delay", expected, sent.map { it.kind })
                }
            }
        }
    }

    @Test
    fun `receipt matrix ignores uncorrelated status and duplicate taps`() = withRelay {
        for (wake in listOf(false, true)) {
            for (arrival in listOf(0L, 1L, 250L, 1_000L, 10_000L)) {
                for (mismatch in ReceiptMismatch.entries) {
                    reset()
                    tap(wake)
                    advance(arrival)
                    val sentReceipt = sent.single().receipt
                    when (mismatch) {
                        ReceiptMismatch.WRONG_PEER -> reply("unknown", from = "other")
                        ReceiptMismatch.WRONG_ID -> reply("unknown", receipt = "different")
                        ReceiptMismatch.EMPTY_ID -> reply("unknown", receipt = "")
                        ReceiptMismatch.MISSING_ID -> reply("unknown", receipt = null)
                    }
                    tap(wake)
                    tap(!wake)
                    assertEquals("$wake/$arrival/$mismatch", listOf(kind(wake)), sent.map { it.kind })

                    reply("unknown", receipt = sentReceipt)
                    tap(wake)
                    assertEquals(listOf(kind(wake), kind(wake)), sent.map { it.kind })
                }
            }
        }
    }

    private enum class RelayObstacle { DISCONNECTED, UNPAIRED, NO_PERMISSION, TWO_RELAYS, NO_DISCOVERY }
    private enum class ReceiptMismatch { WRONG_PEER, WRONG_ID, EMPTY_ID, MISSING_ID }
    private enum class RequestFailure { OFFLINE, ENQUEUE, NO_REPLY }
    private data class Packet(val kind: String, val receipt: String)

    private fun withInstalledWidget(check: RelayFixture.() -> Unit) {
        val manager = mockk<AppWidgetManager>(relaxed = true) {
            every { getAppWidgetIds(any()) } returns intArrayOf(1)
            every { getAppWidgetOptions(1) } returns Bundle()
        }
        mockkStatic(AppWidgetManager::class)
        try {
            every { AppWidgetManager.getInstance(any()) } returns manager
            withRelay(check)
        } finally {
            unmockkStatic(AppWidgetManager::class)
        }
    }

    private fun withRelay(check: RelayFixture.() -> Unit) {
        val fixture = RelayFixture()
        mockkObject(KdeConnect.Companion, BackgroundService.Companion)
        try {
            every { KdeConnect.getInstance() } returns mockk { every { devices } returns fixture.peers }
            every { BackgroundService.ForceRefreshConnections(any()) } returns Unit
            fixture.check()
        } finally {
            fixture.reset()
            unmockkObject(KdeConnect.Companion, BackgroundService.Companion)
        }
    }

    private class RelayFixture {
        private val app = ApplicationProvider.getApplicationContext<Application>()
        private val widget = ResumeWidget()
        private val looper = shadowOf(Looper.getMainLooper())
        val sent = mutableListOf<Packet>()
        var reachable = true
        var paired = true
        var authorized = true
        var enqueueSucceeded = true
        private val bridge = mockk<BridgePlugin> {
            every { peerId } returns "relay"
            every { canResumeInstabox } answers { authorized }
            every { canCheckInstabox } returns true
            every { send(any(), any()) } answers {
                sent += Packet(firstArg<String>(), secondArg<JSONObject>().getString("receipt"))
                enqueueSucceeded
            }
        }
        val peer = mockk<Device> {
            every { isPaired } answers { paired }
            every { isReachable } answers { reachable }
            every { getPlugin(BridgePlugin::class.java) } returns bridge
        }
        val secondPeer = mockk<Device> {
            every { isPaired } returns true
            every { isReachable } returns true
            every { getPlugin(BridgePlugin::class.java) } returns mockk {
                every { canResumeInstabox } returns true
            }
        }
        val peers = ConcurrentHashMap(mapOf("relay" to peer))

        fun reset() {
            widget.onDisabled(app)
            sent.clear()
            peers.clear()
            peers["relay"] = peer
            reachable = true
            paired = true
            authorized = true
            enqueueSucceeded = true
        }

        fun kind(wake: Boolean) = if (wake) "instabox_resume" else "instabox_status"

        fun tap(wake: Boolean) {
            val action = if (wake) "INSTABOX_RESUME" else "INSTABOX_STATUS"
            widget.onReceive(app, Intent("io.github.wrestle_r.pingora.$action"))
        }

        fun advance(milliseconds: Long) = looper.idleFor(milliseconds, TimeUnit.MILLISECONDS)

        fun policyArrived() {
            ResumeWidget.checkIfPresent(app)
            looper.idle()
        }

        fun reply(state: String, from: String = "relay", receipt: String? = sent.last().receipt) {
            val data = JSONObject().put("receipt", receipt).put("state", state)
            ResumeWidget.result(app, from, data)
            looper.idle()
        }
    }

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
