/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.ui.MainActivity

internal data class WidgetSize(val width: Int, val height: Int) {
    companion object {
        fun read(context: Context, manager: AppWidgetManager, id: Int): WidgetSize {
            val options = manager.getAppWidgetOptions(id)
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            return WidgetSize(
                options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 190),
                options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 142)
            )
        }
    }
}

internal val Device.connectionLabel: String
    get() = when {
        !isReachable -> "Offline"
        usesPrivateVpn -> "Connected via VPN"
        else -> "Connected"
    }

internal fun widgetPreferences(context: Context) =
    context.getSharedPreferences("pingora_widgets", Context.MODE_PRIVATE)

internal fun widgetOpenApp(context: Context, id: Int): PendingIntent =
    PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra("pingora_sharing", true)
        .putExtra("pingora_peer", widgetPreferences(context).let {
            it.getString("peer.$id", null) ?: it.getString("clipboard.$id", null)
        }), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

internal fun widgetActivity(context: Context, id: Int, action: String): PendingIntent =
    PendingIntent.getActivity(context, id, Intent(context, ClipboardSendActivity::class.java)
        .setAction("io.github.wrestle_r.pingora.$action")
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
