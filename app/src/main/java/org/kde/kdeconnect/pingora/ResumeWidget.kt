/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.RemoteViews
import org.json.JSONObject
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R
import java.util.UUID

/** The only wake action routes through a single authorized paired desktop. */
class ResumeWidget : AppWidgetProvider() {
    companion object {
        private const val ACTION = "io.github.wrestle_r.pingora.INSTABOX_RESUME"
        private const val REFRESH = "io.github.wrestle_r.pingora.INSTABOX_STATUS"
        private const val STATUS_FRESH_MS = 30_000L
        private const val STARTUP_WINDOW_MS = 90_000L
        private const val PROBE_INTERVAL_MS = 8_000L
        private const val RECONNECT_WINDOW_MS = 10_000L
        private const val RECONNECT_INTERVAL_MS = 250L
        private val handler = Handler(Looper.getMainLooper())
        private var label = "Resume"
        private var compactLabel = label
        private var status = "Check status"
        private var checkedAt = -STATUS_FRESH_MS
        private var request: Request? = null
        private var startup: Startup? = null
        private var connecting = false

        private data class Request(val receipt: String, val peer: String, val wake: Boolean)
        private data class Startup(val peer: String, val deadline: Long)

        private fun relay() = KdeConnect.getInstance().devices.values
            .filter { it.isPaired && it.isReachable }
            .mapNotNull { it.getPlugin<BridgePlugin>() }
            .filter { it.canResumeInstabox }
            .singleOrNull()

        fun refresh(context: Context) {
            handler.post {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, ResumeWidget::class.java))
                if (ids.isNotEmpty()) ResumeWidget().onUpdate(context, manager, ids)
            }
        }

        fun checkIfPresent(context: Context) = handler.post {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ResumeWidget::class.java))
            if (ids.isNotEmpty() && startup == null && SystemClock.elapsedRealtime() - checkedAt >= STATUS_FRESH_MS &&
                relay()?.canCheckInstabox == true) start(context.applicationContext, wake = false)
        }

        private fun finish(context: Context, message: String, shortMessage: String = "Resume", running: Boolean = false) {
            request = null
            startup = null
            status = message
            label = if (running) "Running" else "Resume"
            compactLabel = if (running) label else shortMessage
            val updated = SystemClock.elapsedRealtime()
            checkedAt = updated
            refresh(context)
            if (running) handler.postDelayed({
                if (!connecting && request == null && startup == null && checkedAt == updated) {
                    label = "Resume"
                    compactLabel = label
                    status = "Last seen running"
                    refresh(context)
                }
            }, STATUS_FRESH_MS)
        }

        private fun waitForStartup(context: Context, waiting: Startup) {
            request = null
            val remaining = waiting.deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                finish(context, "Resume accepted. Still waiting for InstaBox.", "Status unknown")
                return
            }
            status = "Waiting for InstaBox…"
            label = "Starting…"
            compactLabel = label
            refresh(context)
            handler.postDelayed({
                if (startup === waiting && request == null) start(context, wake = false)
            }, minOf(PROBE_INTERVAL_MS, remaining))
        }

        fun result(context: Context, peer: String, data: JSONObject) = handler.post {
            val pending = request ?: return@post
            if (peer != pending.peer || data.optString("receipt") != pending.receipt) return@post
            when (data.optString("state")) {
                "running" -> finish(context, "InstaBox is online", running = true)
                "requested" -> if (pending.wake && relay()?.let { it.peerId == peer && it.canCheckInstabox } == true) {
                    val waiting = Startup(peer, SystemClock.elapsedRealtime() + STARTUP_WINDOW_MS)
                    startup = waiting
                    waitForStartup(context, waiting)
                } else {
                    finish(context, "Resume accepted", "Requested")
                }
                "unknown" -> startup?.let { waitForStartup(context, it) }
                    ?: finish(context, "Status unknown", "Status unknown")
                "unavailable" -> finish(context, "Status check unavailable", "Unavailable")
                "busy" -> finish(context, "Try in a minute", "Try again soon")
                else -> finish(context, "Request failed", "Try again")
            }
        }

        private fun start(context: Context, wake: Boolean, reconnectUntil: Long? = null) {
            val waiting = startup
            if (connecting && reconnectUntil == null) return
            connecting = false
            if (request != null || wake && (waiting != null || label == "Running")) return
            if (waiting != null && SystemClock.elapsedRealtime() >= waiting.deadline) {
                waitForStartup(context, waiting)
                return
            }
            if (wake && context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
                finish(context, "Unlock to resume", "Unlock to resume")
                return
            }
            val relay = relay()
            if (waiting != null && (relay?.peerId != waiting.peer || !relay.canCheckInstabox)) {
                waitForStartup(context, waiting)
                return
            }
            if (relay == null) {
                val now = SystemClock.elapsedRealtime()
                val deadline = reconnectUntil ?: now + RECONNECT_WINDOW_MS
                if (now >= deadline) {
                    finish(context, "Wake relay offline", "Relay offline")
                    return
                }
                connecting = true
                if (reconnectUntil == null) {
                    status = "Connecting to wake relay…"
                    label = "Connecting…"
                    compactLabel = label
                    refresh(context)
                    BackgroundService.ForceRefreshConnections(context)
                }
                handler.postDelayed({ start(context, wake, deadline) }, minOf(RECONNECT_INTERVAL_MS, deadline - now))
                return
            }
            if (!wake && !relay.canCheckInstabox) {
                finish(context, "Status check unavailable", "Unavailable")
                return
            }
            val receipt = UUID.randomUUID().toString()
            val kind = if (wake) "instabox_resume" else "instabox_status"
            if (!relay.send(kind, JSONObject().put("receipt", receipt))) {
                if (waiting != null) waitForStartup(context, waiting) else finish(context, "Request failed", "Try again")
                return
            }
            val pending = Request(receipt, relay.peerId, wake)
            request = pending
            if (waiting == null) {
                status = if (wake) "Requesting resume…" else "Checking status…"
                label = if (wake) "Requesting…" else "Checking…"
                compactLabel = label
            }
            refresh(context)
            handler.postDelayed({
                if (request === pending) {
                    if (waiting != null) waitForStartup(context, waiting) else finish(context, "No reply from relay", "No reply")
                }
            }, if (wake) 65_000 else minOf(15_000, waiting?.let { it.deadline - SystemClock.elapsedRealtime() } ?: 15_000))
        }
    }

    override fun onEnabled(context: Context) { checkIfPresent(context) }

    override fun onDeleted(context: Context, ids: IntArray) {
        WidgetAppearance.remove(context, ids)
    }

    override fun onDisabled(context: Context) {
        handler.removeCallbacksAndMessages(null)
        request = null
        startup = null
        connecting = false
        checkedAt = -STATUS_FRESH_MS
        status = "Check status"
        label = "Resume"
        compactLabel = label
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        for (id in ids) {
            val views = widgetViews(context, manager, id) { size ->
                val compact = size.height < 90 + 20 * fontScale
                val layout = if (compact) R.layout.pingora_resume_widget_compact else R.layout.pingora_resume_widget
                RemoteViews(context.packageName, layout).apply {
                    val idle = !connecting && request == null && startup == null
                    val canWake = idle && label != "Running"
                    setTextViewText(R.id.resume_widget_button, if (compact) compactLabel else label)
                    if (!compact) setTextViewText(R.id.resume_widget_status, status)
                    setContentDescription(R.id.resume_widget_button, if (canWake) "Resume InstaBox, $status" else status)
                    setOnClickPendingIntent(R.id.resume_widget_button, if (canWake) action(context, id, ACTION) else null)
                    setOnClickPendingIntent(R.id.resume_widget_refresh, if (idle) action(context, id, REFRESH) else null)
                }
            }
            manager.updateAppWidget(id, views)
        }
    }

    private fun action(context: Context, id: Int, action: String) = PendingIntent.getBroadcast(
        context, id, Intent(context, ResumeWidget::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION -> start(context.applicationContext, wake = true)
            REFRESH -> if (startup == null) start(context.applicationContext, wake = false)
        }
    }
}
