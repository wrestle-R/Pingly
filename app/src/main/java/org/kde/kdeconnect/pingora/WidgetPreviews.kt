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

    private fun preview(context: Context, laptop: Boolean): RemoteViews =
        RemoteViews(context.packageName, if (laptop) R.layout.pingora_laptop_widget else R.layout.pingora_clipboard_widget_compact).apply {
            if (laptop) {
                setTextViewText(R.id.laptop_widget_name, "My laptop")
                setTextViewText(R.id.laptop_widget_battery, "84%")
                setTextViewText(R.id.laptop_widget_cpu, "12%")
                setTextViewText(R.id.laptop_widget_ram, "37%")
                setTextViewText(R.id.laptop_widget_connection, "Connected")
                setTextViewText(R.id.laptop_widget_internet, "Internet online")
                setViewVisibility(R.id.laptop_widget_internet, View.VISIBLE)
                setViewVisibility(R.id.laptop_widget_power, View.VISIBLE)
            }
        }
}
