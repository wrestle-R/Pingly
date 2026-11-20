/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.app.Activity
import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.KdeConnect

internal const val CLIPBOARD_LAPTOP_KEY = "clipboard_laptop"

internal val Device.canBeClipboardLaptop: Boolean
    get() = isPaired && (deviceType == DeviceType.LAPTOP || deviceType == DeviceType.DESKTOP)

internal fun clipboardLaptop(context: Context, peers: Collection<Device>): Device? {
    val id = widgetPreferences(context).getString(CLIPBOARD_LAPTOP_KEY, null) ?: return null
    return peers.firstOrNull { it.deviceId == id && it.canBeClipboardLaptop }
}

/** The main app already has focus. Only the launcher, tile and chooser need another activity. */
internal fun sendClipboardFrom(activity: Activity) {
    val target = clipboardLaptop(activity, KdeConnect.getInstance().devices.values)
    val bridge = target?.getPlugin<BridgePlugin>()
    if (activity.hasWindowFocus() && bridge?.isReady == true) {
        sendFocusedClipboard(activity, bridge, AppWidgetManager.INVALID_APPWIDGET_ID)
    } else {
        activity.startActivity(Intent(activity, ClipboardSendActivity::class.java))
    }
}

private fun sendFocusedClipboard(
    context: Context,
    bridge: BridgePlugin,
    widgetId: Int,
    onSourceRead: (() -> Unit)? = null,
) {
    val app = context.applicationContext
    var closeActivity = onSourceRead
    fun close() {
        val callback = closeActivity
        closeActivity = null
        callback?.invoke()
    }
    fun done(message: String) {
        close()
        ClipboardWidget.label(app, widgetId, message, reset = true)
    }

    if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
        done("Unlock to send")
        return
    }
    if (!bridge.canSend("clipboard")) {
        done("Clipboard disabled")
        return
    }
    if (bridge.clipboard.busy) {
        done("Already sending")
        return
    }
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
    if (clip == null || clip.itemCount == 0) {
        done("Clipboard empty")
        return
    }
    if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
        ClipboardWidget.label(app, widgetId, "Sending…")
    }
    bridge.clipboard.send(clip, sourceRead = ::close, done = ::done)
}

/** A transparent focused activity lets Android authorize the widget's clipboard read. */
class ClipboardSendActivity : AppCompatActivity() {
    companion object {
        internal const val CHOOSE_LAPTOP = "io.github.wrestle_r.pingora.clipboard-target"
    }

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
        val devices = KdeConnect.getInstance().devices.values
        val target = clipboardLaptop(this, devices)
        if (intent.action == CHOOSE_LAPTOP || target == null) {
            val peers = devices.filter { it.canBeClipboardLaptop }
                .withoutOfflineDuplicates()
                .sortedWith(compareBy({ it.name.lowercase() }, { it.deviceId }))
            if (peers.isEmpty()) {
                done("Pair a laptop")
                return
            }
            MaterialAlertDialogBuilder(this).setTitle("Choose your clipboard laptop")
                .setItems(peers.map { peer ->
                    val name = if (peers.count { it.name == peer.name } > 1) "${peer.name} (${peer.deviceId.takeLast(6)})" else peer.name
                    "$name · ${peer.connectionLabel}"
                }.toTypedArray()) { _, index ->
                    val peer = peers[index]
                    widgetPreferences(this).edit().putString(CLIPBOARD_LAPTOP_KEY, peer.deviceId).apply()
                    ClipboardWidget.refresh(this)
                    done("Laptop saved")
                }.setOnCancelListener { finish() }.show()
        } else {
            chosen = target
            send()
        }
    }

    private fun send() {
        val peer = chosen ?: return
        if (isFinishing || !hasWindowFocus()) return
        handler.removeCallbacksAndMessages(null)
        if (clipboardLaptop(this, KdeConnect.getInstance().devices.values) !== peer) {
            done("Choose your laptop")
            return
        }
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
        chosen = null
        sendFocusedClipboard(this, bridge, widgetId) {
            if (!isFinishing) finish()
        }
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
