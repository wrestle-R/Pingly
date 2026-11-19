/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

internal object WidgetAppearance {
    private const val MIN_OPACITY = 60
    private const val MAX_OPACITY = 100

    fun opacity(context: Context, id: Int): Int =
        widgetPreferences(context).getInt("opacity.$id", MAX_OPACITY).coerceIn(MIN_OPACITY, MAX_OPACITY)

    fun remove(context: Context, ids: IntArray) {
        val editor = widgetPreferences(context).edit()
        ids.forEach { editor.remove("opacity.$it") }
        editor.apply()
    }

    fun show(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val providers = listOf(
            "Devices" to LaptopWidget(),
            "Clipboard" to ClipboardWidget(),
            "InstaBox Resume" to ResumeWidget(),
        )
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).roundToInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val changes = providers.flatMap { (name, provider) ->
            val ids = manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
            ids.mapIndexed { index, id ->
                val label = if (ids.size == 1) name else "$name ${index + 1}"
                val original = opacity(context, id)
                val reading = TextView(context).apply {
                    text = "$label · $original%"
                    textSize = 15f
                }
                content.addView(reading)
                val slider = Slider(context).apply {
                    contentDescription = "$label background opacity"
                    valueFrom = MIN_OPACITY.toFloat()
                    valueTo = MAX_OPACITY.toFloat()
                    stepSize = 1f
                    value = original.toFloat()
                    addOnChangeListener { _, value, _ -> reading.text = "$label · ${value.roundToInt()}%" }
                }
                content.addView(slider)
                val save: () -> Unit = {
                    val value = slider.value.roundToInt()
                    if (value != original && id in manager.getAppWidgetIds(ComponentName(context, provider.javaClass))) {
                        widgetPreferences(context).edit().putInt("opacity.$id", value).apply()
                        provider.onUpdate(context, manager, intArrayOf(id))
                    }
                }
                save
            }
        }
        if (changes.isEmpty()) {
            MaterialAlertDialogBuilder(context).setTitle("Widget opacity")
                .setMessage("Add a Pingora widget to change its background opacity.")
                .setPositiveButton("OK", null).show()
            return
        }
        val scroll = ScrollView(context).apply { addView(content) }
        MaterialAlertDialogBuilder(context).setTitle("Widget opacity").setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> changes.forEach { it() } }
            .show()
    }
}
