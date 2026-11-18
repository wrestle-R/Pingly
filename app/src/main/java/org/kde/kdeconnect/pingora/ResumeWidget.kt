/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import org.json.JSONObject
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R
import java.util.UUID

/** The only wake action routes through a single authorized paired desktop. */
class ResumeWidget : AppWidgetProvider() {
    companion object {
        private const val ACTION = "io.github.wrestle_r.pingora.INSTABOX_RESUME"
        private const val REFRESH = "io.github.wrestle_r.pingora.INSTABOX_STATUS"
        private val handler = Handler(Looper.getMainLooper())
        private var label = "Resume"
        private var status = "Check status"
        private var checkedAt = -60_000L
        private var request: String? = null
        private var relayPeer: String? = null

        private fun relay() = KdeConnect.getInstance().devices.values
            .filter { it.isPaired && it.isReachable }
            .mapNotNull { it.getPlugin<BridgePlugin>() }
            .filter { it.canResumeInstabox }
            .singleOrNull()

        fun isAvailable() = relay() != null

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
            if (ids.isNotEmpty() && android.os.SystemClock.elapsedRealtime() - checkedAt >= 30_000 &&
                relay()?.canCheckInstabox == true) start(context.applicationContext, wake = false)
        }

        private fun finish(context: Context, message: String, running: Boolean = false) {
            request = null
            relayPeer = null
            status = message
            label = if (running) "Running" else "Resume"
            val updated = android.os.SystemClock.elapsedRealtime()
            checkedAt = updated
            refresh(context)
            if (running) handler.postDelayed({
                if (request == null && checkedAt == updated) {
                    label = "Resume"
                    status = "Last seen running"
                    refresh(context)
                }
            }, 30_000)
        }

        fun result(context: Context, peer: String, data: JSONObject) = handler.post {
            if (request == null || peer != relayPeer || data.optString("receipt") != request) return@post
            when (data.optString("state")) {
                "running" -> finish(context, "InstaBox is online", running = true)
                "requested" -> {
                    finish(context, "Resume requested")
                    handler.postDelayed({ if (relay()?.canCheckInstabox == true) start(context, wake = false) }, 10_000)
                }
                "unknown" -> finish(context, "Status unknown")
                "unavailable" -> finish(context, "Status check unavailable")
                "busy" -> finish(context, "Try in a minute")
                else -> finish(context, "Request failed")
            }
        }

        private fun start(context: Context, wake: Boolean) {
            if (request != null) return
            if (wake && context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
                finish(context, "Unlock to resume")
                return
            }
            val relay = relay()
            if (relay == null) {
                org.kde.kdeconnect.BackgroundService.ForceRefreshConnections(context)
                finish(context, "Wake relay offline")
                return
            }
            if (!wake && !relay.canCheckInstabox) {
                finish(context, "Status check unavailable")
                return
            }
            val receipt = UUID.randomUUID().toString()
            val kind = if (wake) "instabox_resume" else "instabox_status"
            if (!relay.send(kind, JSONObject().put("receipt", receipt))) {
                finish(context, "Request failed")
                return
            }
            relayPeer = relay.peerId
            request = receipt
            status = if (wake) "Requesting resume…" else "Checking status…"
            label = if (wake) "Requesting…" else "Resume"
            refresh(context)
            handler.postDelayed({
                if (request == receipt) finish(context, "No reply from relay")
            }, if (wake) 65_000 else 15_000)
        }
    }

    override fun onEnabled(context: Context) { checkIfPresent(context) }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val view = RemoteViews(context.packageName, R.layout.pingora_resume_widget)
            view.setTextViewText(R.id.resume_widget_button, label)
            view.setTextViewText(R.id.resume_widget_status, status)
            view.setContentDescription(R.id.resume_widget_button, if (label == "Resume") "Resume InstaBox" else label)
            view.setOnClickPendingIntent(R.id.resume_widget_button, if (label == "Running") null else action(context, id, ACTION))
            view.setOnClickPendingIntent(R.id.resume_widget_refresh, action(context, id, REFRESH))
            manager.updateAppWidget(id, view)
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
            REFRESH -> start(context.applicationContext, wake = false)
        }
    }
}
