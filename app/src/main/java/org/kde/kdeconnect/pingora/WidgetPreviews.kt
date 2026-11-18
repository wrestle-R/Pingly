/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import org.kde.kdeconnect_tp.R

object WidgetPreviews {
    private var registered = false

    fun register(context: Context) {
        if (Build.VERSION.SDK_INT < 35 || registered) return
        val manager = AppWidgetManager.getInstance(context)
        val clipboard = manager.setWidgetPreview(ComponentName(context, ClipboardWidget::class.java),
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, preview(context, false))
        val laptop = manager.setWidgetPreview(ComponentName(context, LaptopWidget::class.java),
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, preview(context, true))
        registered = clipboard && laptop
    }

    fun pin(context: Context, laptop: Boolean) {
        if (Build.VERSION.SDK_INT < 26) return
        val provider = if (laptop) LaptopWidget::class.java else ClipboardWidget::class.java
        AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, provider), Bundle().apply {
            putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, preview(context, laptop))
        }, null)
    }

    private fun preview(context: Context, laptop: Boolean): RemoteViews {
        if (!laptop) return RemoteViews(context.packageName, R.layout.pingora_clipboard_widget_compact)
        return RemoteViews(context.packageName, R.layout.pingora_devices_widget).apply {
            addView(R.id.devices_widget_rows, deviceWidgetRow(context, "Laptop", "Connected", "84%", "CPU 12% · RAM 37%"))
            addView(R.id.devices_widget_rows, deviceWidgetRow(context, "InstaBox", "Offline", ""))
        }
    }
}

internal fun deviceWidgetRow(context: Context, name: String, status: String, battery: String, usage: String = "") =
    RemoteViews(context.packageName, R.layout.pingora_devices_widget_row).apply {
        setTextViewText(R.id.devices_widget_name, name)
        setTextViewText(R.id.devices_widget_status, status)
        setTextViewText(R.id.devices_widget_reading, battery)
        setViewVisibility(R.id.devices_widget_reading, if (battery.isEmpty()) View.GONE else View.VISIBLE)
        setTextViewText(R.id.devices_widget_usage, usage)
        setViewVisibility(R.id.devices_widget_usage, if (usage.isEmpty()) View.GONE else View.VISIBLE)
    }
