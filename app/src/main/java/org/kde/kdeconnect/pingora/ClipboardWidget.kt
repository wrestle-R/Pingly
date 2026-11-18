/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R

class ClipboardWidget : AppWidgetProvider() {
    companion object {
        private val labels = mutableMapOf<Int, String>()

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ClipboardWidget::class.java))
            ClipboardWidget().onUpdate(context, manager, ids)
        }

        fun label(context: Context, id: Int, value: String, reset: Boolean = false) {
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
                android.widget.Toast.makeText(context, value, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            synchronized(labels) { labels[id] = value }
            refresh(context)
            if (reset) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                synchronized(labels) { if (labels[id] == value) labels.remove(id) }
                refresh(context)
            }, 3500)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val peers = KdeConnect.getInstance().devices.values.filter { it.isPaired }
        for (id in ids) {
            val target = widgetPreferences(context).getString("clipboard.$id", null)
            val peer = if (target == null) peers.singleOrNull() else peers.firstOrNull { it.deviceId == target }
            val label = synchronized(labels) { labels[id] }
            val views = widgetViews(context, manager, id) { size ->
                val compact = size.height < 132
                val choosing = peers.size > 1 || peer == null && peers.isNotEmpty()
                val layout = if (compact) R.layout.pingora_clipboard_widget_compact else R.layout.pingora_clipboard_widget
                val view = RemoteViews(context.packageName, layout)
                if (!compact) {
                    view.setTextViewText(R.id.pingora_widget_status, peer?.let {
                        "${it.name} · ${if (it.isReachable && it.getPlugin<BridgePlugin>()?.canSend("clipboard") != true) "Clipboard unavailable" else it.connectionLabel}"
                    } ?: if (peers.isEmpty()) "Pair a laptop in Pingora" else "Choose a laptop")
                    view.setTextViewCompoundDrawables(R.id.pingora_widget_status, if (peer?.usesPrivateVpn == true) R.drawable.ic_widget_vpn else 0, 0, 0, 0)
                }
                if (compact) {
                    val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
                    val showTitle = size.width >= (if (choosing) 256 else 200) * fontScale
                    view.setViewVisibility(R.id.clipboard_widget_title, if (showTitle) View.VISIBLE else View.GONE)
                    view.setViewVisibility(R.id.clipboard_widget_icon, if (!showTitle && !choosing) View.VISIBLE else View.GONE)
                }
                view.setViewVisibility(R.id.clipboard_widget_choose, if (choosing) View.VISIBLE else View.GONE)
                view.setTextViewText(R.id.pingora_widget_send, label ?: if (peer == null && peers.isNotEmpty()) "Choose laptop" else "Send clipboard")
                view.setOnClickPendingIntent(R.id.widget_root, widgetOpenApp(context, id))
                view.setOnClickPendingIntent(R.id.clipboard_widget_header, widgetOpenApp(context, id))
                view.setOnClickPendingIntent(R.id.clipboard_widget_choose, widgetActivity(context, id, "clipboard-target"))
                view.setOnClickPendingIntent(R.id.pingora_widget_send, widgetActivity(context, id, "clipboard-send"))
                view
            }
            manager.updateAppWidget(id, views)
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val editor = widgetPreferences(context).edit()
        ids.forEach { editor.remove("clipboard.$it") }
        editor.apply()
        synchronized(labels) { ids.forEach(labels::remove) }
    }
}
