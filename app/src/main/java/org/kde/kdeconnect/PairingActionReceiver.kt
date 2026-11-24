/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles only the one-shot actions created for the current pairing notification. */
class PairingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accept = when (intent.action) {
            ACCEPT -> true
            REJECT -> false
            else -> return
        }
        val deviceId = intent.getStringExtra(DEVICE_ID) ?: return
        val token = intent.getStringExtra(TOKEN) ?: return
        KdeConnect.getInstance().getDevice(deviceId)?.respondToPairingNotification(token, accept)
    }

    companion object {
        private const val ACCEPT = "org.kde.kdeconnect.PAIRING_ACCEPTED"
        private const val REJECT = "org.kde.kdeconnect.PAIRING_REJECTED"
        private const val DEVICE_ID = "deviceId"
        private const val TOKEN = "token"

        internal fun intent(context: Context, deviceId: String, token: String, accept: Boolean): Intent =
            Intent(context, PairingActionReceiver::class.java).apply {
                action = if (accept) ACCEPT else REJECT
                // Android compares PendingIntent identifiers without considering extras.
                data = android.net.Uri.Builder().scheme("pingly").authority("pairing").appendPath(deviceId).build()
                putExtra(DEVICE_ID, deviceId)
                putExtra(TOKEN, token)
            }
    }
}
