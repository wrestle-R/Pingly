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
        private val handler = Handler(Looper.getMainLooper())
        private var label = "Resume"
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

        private fun finish(context: Context, message: String) {
            request = null
            relayPeer = null
            label = message
            refresh(context)
            handler.postDelayed({
                if (request == null && label == message) {
                    label = "Resume"
                    refresh(context)
                }
            }, 5000)
        }

        fun result(context: Context, peer: String, data: JSONObject) = handler.post {
            if (request == null || peer != relayPeer || data.optString("receipt") != request) return@post
            finish(context, when (data.optString("state")) {
                "requested" -> "Resume requested"
                "busy" -> "Try in a minute"
                else -> "Resume failed"
            })
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val view = RemoteViews(context.packageName, R.layout.pingora_resume_widget)
            view.setTextViewText(R.id.resume_widget_button, label)
            view.setContentDescription(R.id.resume_widget_button, if (label == "Resume") "Resume InstaBox" else label)
            view.setOnClickPendingIntent(R.id.resume_widget_button, PendingIntent.getBroadcast(
                context, id, Intent(context, ResumeWidget::class.java).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            manager.updateAppWidget(id, view)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION || request != null) return
        val app = context.applicationContext
        if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            finish(app, "Unlock to resume")
            return
        }
        val relay = relay()
        if (relay == null) {
            org.kde.kdeconnect.BackgroundService.ForceRefreshConnections(context)
            finish(app, "Wake relay offline")
            return
        }
        val receipt = UUID.randomUUID().toString()
        if (!relay.send("instabox_resume", JSONObject().put("receipt", receipt))) {
            finish(app, "Resume failed")
            return
        }
        relayPeer = relay.peerId
        request = receipt
        label = "Requesting…"
        refresh(app)
        handler.postDelayed({
            if (request == receipt) finish(app, "No reply from relay")
        }, 55_000)
    }
}
