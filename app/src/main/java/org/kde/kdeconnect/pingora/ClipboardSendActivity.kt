/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect

/** A transparent focused activity lets Android authorize the widget's clipboard read. */
class ClipboardSendActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val widgetId get() = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    private var started = false
    private var chosen: Device? = null
    private var attempts = 0

    override fun onWindowFocusChanged(focus: Boolean) {
        super.onWindowFocusChanged(focus)
        if (focus && !started) {
            started = true
            prepare()
        } else if (focus && chosen != null) {
            handler.post(::send)
        }
    }

    private fun prepare() {
        val peers = KdeConnect.getInstance().devices.values.filter { it.isPaired }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.deviceId }))
        val choose = intent.action?.endsWith(".clipboard-target") == true
        val key = "clipboard.$widgetId"
        val hint = intent.getStringExtra("peer") ?: widgetPreferences(this).getString(key, null)
        val target = if (hint == null) peers.singleOrNull() else peers.firstOrNull { it.deviceId == hint }
        if (peers.isEmpty()) {
            done("Pair a laptop")
        } else if (choose || target == null) {
            MaterialAlertDialogBuilder(this).setTitle("Choose a laptop")
                .setItems(peers.map { peer ->
                    val name = if (peers.count { it.name == peer.name } > 1) "${peer.name} (${peer.deviceId.takeLast(6)})" else peer.name
                    "$name · ${peer.connectionLabel}"
                }.toTypedArray()) { _, index ->
                    val peer = peers[index]
                    if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        widgetPreferences(this).edit().putString(key, peer.deviceId).apply()
                        ClipboardWidget.refresh(this)
                    }
                    if (choose) finish() else {
                        chosen = peer
                        handler.post(::send)
                    }
                }.setOnCancelListener { finish() }.show()
        } else {
            chosen = target
            handler.post(::send)
        }
    }

    private fun send() {
        val peer = chosen ?: return
        if (isFinishing || !hasWindowFocus()) return
        handler.removeCallbacksAndMessages(null)
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            done("Unlock to send")
            return
        }
        val bridge = peer.getPlugin<BridgePlugin>()
        if (bridge?.isReady != true) {
            if (attempts++ == 0) {
                ClipboardWidget.label(applicationContext, widgetId, "Connecting…")
                org.kde.kdeconnect.BackgroundService.ForceRefreshConnections(this)
            }
            if (attempts < 26) handler.postDelayed(::send, 200) else done("Laptop offline")
            return
        }
        if (!bridge.canSend("clipboard")) {
            done("Clipboard disabled")
            return
        }
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        if (clip == null || clip.itemCount == 0) {
            done("Clipboard empty")
            return
        }
        val item = clip.getItemAt(0)
        val isImage = clip.description.hasMimeType("image/*")
        if (bridge.clipboard.busy) {
            finish()
            return
        }
        val context = applicationContext
        val id = widgetId
        ClipboardWidget.label(context, id, "Sending…")
        bridge.clipboard.send(item, isImage) { ClipboardWidget.label(context, id, it, reset = true) }
        finish()
    }

    private fun done(message: String) {
        ClipboardWidget.label(applicationContext, widgetId, message, reset = true)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
