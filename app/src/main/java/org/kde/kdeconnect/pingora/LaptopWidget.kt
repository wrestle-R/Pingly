/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R

/** Controls use explicit broadcasts, never navigate to an activity. Each widget pins its device. */
class LaptopWidget : AppWidgetProvider() {
    companion object {
        private const val ACTION = "io.github.wrestle_r.pingora.LAPTOP_WIDGET"
        fun profileLabel(value: String)=when(value) {"power-saver"->"Battery saving";"balanced"->"Efficiency";"performance"->"Powerful";else->"Unknown"}
        private fun metricValues(data: JSONObject): Pair<String, String> {
            val cpu=data.optDouble("cpu_percent",Double.NaN)
            val used=data.optDouble("ram_used_bytes",Double.NaN);val total=data.optDouble("ram_total_bytes",Double.NaN)
            val cpuText=if(cpu.isFinite() && cpu in 0.0..100.0) "${cpu.toInt()}%" else "—"
            val ramText=if(used.isFinite() && total>0 && used in 0.0..total) "${(used/total*100).toInt()}%" else "—"
            return cpuText to ramText
        }
        fun metrics(data: JSONObject): String {
            val (cpu, ram) = metricValues(data)
            return "CPU $cpu · RAM $ram"
        }

        fun hasWidgets(context: Context) = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,LaptopWidget::class.java)).isNotEmpty()
        fun refresh(context: Context) {
            val manager=AppWidgetManager.getInstance(context)
            val ids=manager.getAppWidgetIds(ComponentName(context,LaptopWidget::class.java))
            if(ids.isNotEmpty()) LaptopWidget().onUpdate(context,manager,ids)
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val preferences=context.getSharedPreferences("pingora_widgets",Context.MODE_PRIVATE)
        val peers=KdeConnect.getInstance().devices.values.filter {it.isPaired}
        for(id in ids) {
            var target=preferences.getString("peer.$id",null)
            if(target==null) {
                target=peers.singleOrNull()?.deviceId
                if(target!=null) preferences.edit().putString("peer.$id",target).apply()
            }
            val peer=peers.firstOrNull {it.deviceId==target}
            val bridge=peer?.getPlugin<BridgePlugin>()
            val age=bridge?.let {SystemClock.elapsedRealtime()-it.laptopStatusAt} ?: Long.MAX_VALUE
            val fresh=peer?.isReachable==true && bridge?.isReady==true && bridge.laptopStatusAt>0 && age in 0..90_000
            val saved=target?.let {LaptopTelemetry.read(context,it)} ?: LaptopTelemetry.Snapshot(JSONObject(),0)
            val status=saved.data
            val battery=status.optJSONObject("battery")
            val level=battery?.optInt("level",-1) ?: -1
            val slumber=status.optJSONObject("slumber")
            val size = WidgetSize.read(context, manager, id)
            val showSync = size.height >= 240 && saved.receivedAt > 0
            val showElapsed = size.height >= 260 && showSync && System.currentTimeMillis() >= saved.receivedAt
            val view=RemoteViews(context.packageName,R.layout.pingora_laptop_widget)
            view.setOnClickPendingIntent(R.id.widget_root, widgetOpenApp(context, id))
            view.setOnClickPendingIntent(R.id.laptop_widget_power, widgetOpenApp(context, id))
            val metricsVisibility = if (size.width >= 216) View.VISIBLE else View.GONE
            view.setViewVisibility(R.id.laptop_widget_cpu_column, metricsVisibility)
            view.setViewVisibility(R.id.laptop_widget_ram_column, metricsVisibility)
            val (cpu, ram) = metricValues(status)
            view.setTextViewText(R.id.laptop_widget_cpu, cpu)
            view.setTextViewText(R.id.laptop_widget_ram, ram)
            view.setTextViewText(R.id.laptop_widget_name,peer?.name ?: "Laptop status")
            view.setTextViewText(R.id.laptop_widget_battery,if(level in 0..100) "$level%" else "—")
            view.setTextViewText(R.id.laptop_widget_connection,when {peer==null->if(peers.isEmpty()) "Pair in Pingora" else "Choose a laptop";peer.isReachable!=true->"Offline · Saved reading";!fresh->"Updating · Saved reading";else->peer.connectionLabel})
            view.setTextViewText(R.id.laptop_widget_internet,(if(battery?.optBoolean("charging")==true) "Charging · " else "")+when(status.optString("internet")) {"full"->"Internet online";"portal"->"Sign-in needed";"limited"->"Internet limited";"none"->"No internet";else->"Internet unknown"})
            val profile=status.optString("power_profile")
            view.setImageViewResource(R.id.laptop_widget_power,when(profile) {"power-saver"->R.drawable.ic_profile_saver;"performance"->R.drawable.ic_profile_performance;else->R.drawable.ic_profile_balanced})
            view.setViewVisibility(R.id.laptop_widget_power,if(profile in listOf("power-saver","balanced","performance")) android.view.View.VISIBLE else View.GONE)
            view.setContentDescription(R.id.laptop_widget_power,"Last reported profile: ${profileLabel(profile)}")
            view.setTextViewText(R.id.laptop_widget_lid,if(slumber?.optBoolean("available")==true) "Lid mode · ${slumber.optString("lid_action","unknown")}" else "Lid mode unavailable")
            view.setTextViewText(R.id.laptop_widget_age,if(saved.receivedAt>0) "Last sync · ${android.text.format.DateFormat.format("d MMM, HH:mm",saved.receivedAt)}" else "Not synced yet")
            view.setChronometer(R.id.laptop_widget_elapsed,SystemClock.elapsedRealtime()-(System.currentTimeMillis()-saved.receivedAt).coerceAtLeast(0),"%s since last sync",showElapsed)
            val intent=Intent(context,LaptopWidget::class.java).setAction(ACTION).putExtra("widget",id).putExtra("command","refresh")
            view.setOnClickPendingIntent(R.id.laptop_widget_refresh,PendingIntent.getBroadcast(context,id*4,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            view.setViewVisibility(R.id.laptop_widget_internet, if (size.height >= 152 && saved.receivedAt > 0) View.VISIBLE else View.GONE)
            view.setViewVisibility(R.id.laptop_widget_age, if (showSync) View.VISIBLE else View.GONE)
            view.setViewVisibility(R.id.laptop_widget_elapsed, if (showElapsed) View.VISIBLE else View.GONE)
            view.setViewVisibility(R.id.laptop_widget_lid, if (size.height >= 285 && slumber?.optBoolean("available") == true) View.VISIBLE else View.GONE)
            view.setViewVisibility(R.id.laptop_widget_connection, if (size.height >= 145 || !fresh || peer?.usesPrivateVpn == true) View.VISIBLE else View.GONE)
            val choosing = peers.size > 1 || peer == null && peers.isNotEmpty()
            view.setViewVisibility(R.id.laptop_widget_choose, if (choosing) View.VISIBLE else View.GONE)
            view.setOnClickPendingIntent(R.id.laptop_widget_choose, widgetActivity(context, id, "laptop-target"))
            if (choosing && size.width < 220) view.setViewVisibility(R.id.laptop_widget_power, View.GONE)
            view.setTextViewCompoundDrawables(R.id.laptop_widget_connection, if (peer?.usesPrivateVpn == true) R.drawable.ic_widget_vpn else 0, 0, 0, 0)
            manager.updateAppWidget(id,view)
        }
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context,intent)
        if(intent.action!=ACTION) return
        val id=intent.getIntExtra("widget",-1)
        val target=context.getSharedPreferences("pingora_widgets",Context.MODE_PRIVATE).getString("peer.$id",null)
        val bridge=target?.let {KdeConnect.getInstance().devices[it]?.getPlugin<BridgePlugin>()}
        org.kde.kdeconnect.BackgroundService.ForceRefreshConnections(context)
        val command=intent.getStringExtra("command")
        if(command=="refresh") bridge?.requestLaptopStatus()
        refresh(context)
    }
    override fun onDeleted(context: Context, ids: IntArray) {
        val editor=context.getSharedPreferences("pingora_widgets",Context.MODE_PRIVATE).edit()
        ids.forEach {editor.remove("peer.$it")};editor.apply()
    }
}
