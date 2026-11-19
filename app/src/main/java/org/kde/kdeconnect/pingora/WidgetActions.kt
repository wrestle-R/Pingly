/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.os.Build
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class WidgetSize(val width: Int, val height: Int)

internal fun Context.widgetLineHeight(sp: Float): Int {
    val metrics = resources.displayMetrics
    val paint = Paint().apply { textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics) }
    val font = paint.fontMetrics
    return ceil((font.descent - font.ascent) / metrics.density).toInt()
}

internal fun widgetViews(
    context: Context,
    manager: AppWidgetManager,
    id: Int,
    render: (WidgetSize) -> RemoteViews,
): RemoteViews {
    val alpha = (WidgetAppearance.opacity(context, id) * 255 / 100f).roundToInt()
    fun renderWithOpacity(size: WidgetSize) = render(size).apply {
        setInt(R.id.widget_surface, "setImageAlpha", alpha)
    }
    val options = manager.getAppWidgetOptions(id)
    if (Build.VERSION.SDK_INT >= 31) {
        val sizes = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        if (!sizes.isNullOrEmpty() && sizes.size <= 16) {
            return RemoteViews(sizes.associateWith { renderWithOpacity(WidgetSize(it.width.toInt(), it.height.toInt())) })
        }
    }
    val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    return renderWithOpacity(WidgetSize(
        options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 190),
        options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 142)
    ))
}

internal val Device.connectionLabel: String
    get() = when {
        !isReachable -> "Offline"
        usesTailscale -> "Connected via Tailscale"
        usesPrivateVpn -> "Connected via VPN"
        else -> "Connected"
    }

internal fun List<Device>.withoutOfflineDuplicates(): List<Device> {
    val reachableNames = filter { it.isReachable }.mapTo(HashSet()) { it.name }
    return filter { it.isReachable || it.name !in reachableNames }
}

internal fun widgetPreferences(context: Context) =
    context.getSharedPreferences("pingora_widgets", Context.MODE_PRIVATE)

internal fun widgetOpenApp(
    context: Context,
    id: Int,
    peer: String? = widgetPreferences(context).let {
        it.getString("peer.$id", null)
    },
): PendingIntent = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
    .setData(android.net.Uri.Builder().scheme("pingora-widget").authority("open")
        .appendPath(id.toString()).appendPath(peer.orEmpty()).build())
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    .putExtra("pingora_sharing", true).putExtra("pingora_peer", peer),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

internal fun widgetActivity(context: Context, id: Int, action: String): PendingIntent =
    PendingIntent.getActivity(context, id, Intent(context, ClipboardSendActivity::class.java)
        .setAction("io.github.wrestle_r.pingora.$action")
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
