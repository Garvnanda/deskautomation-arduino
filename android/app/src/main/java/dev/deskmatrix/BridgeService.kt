package dev.deskmatrix

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Person
import android.media.MediaMetadata
import android.os.Build
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Sends phone events to the board. Android keeps a notification listener bound
 * (and rebinds it after reboot), so no foreground service or boot receiver is needed.
 *
 * Calls are read from the dialer's incoming-call notification, so no phone or
 * call-log permission is needed either.
 */
class BridgeService : NotificationListenerService() {
    private val apps = mapOf(
        "com.whatsapp" to "whatsapp",
        "com.instagram.android" to "instagram",
        "com.google.android.gm" to "gmail",
    )
    private val mediaApps = setOf("com.spotify.music", "com.google.android.apps.youtube.music")
    private val seen = LinkedHashSet<Int>()
    private var ringingKey: String? = null
    private var lastBattery = -1
    private val controllers = mutableListOf<Pair<MediaController, MediaController.Callback>>()

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 /
                i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            // Send only around the thresholds; the board keeps the "already alerted" flags.
            val bucket = when { level < 20 -> 0; level < 25 -> 1; level < 95 -> 2; level < 100 -> 3; else -> 4 }
            if (bucket != lastBattery) {
                lastBattery = bucket
                send("battery", "level" to level)
            }
        }
    }

    override fun onListenerConnected() {
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val msm = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, BridgeService::class.java)
        msm.addOnActiveSessionsChangedListener({ watchMedia(it) }, me)
        watchMedia(msm.getActiveSessions(me))
    }

    override fun onListenerDisconnected() {
        runCatching { unregisterReceiver(battery) }
        watchMedia(null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (n.category == Notification.CATEGORY_CALL) {
            // An answered call shows a running chronometer; a ringing one does not.
            val ringing = !n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
            if (ringing && ringingKey == null) {
                ringingKey = sbn.key
                val person = if (Build.VERSION.SDK_INT >= 31)
                    n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name?.toString()
                else null
                val number = text.takeIf { it.count(Char::isDigit) >= 5 } ?: ""
                send("call", "state" to "ringing", "name" to (person ?: title), "number" to number)
            } else if (!ringing && ringingKey == sbn.key) {
                ringingKey = null
                send("call", "state" to "answered")
            }
            return
        }

        val app = apps[sbn.packageName]
            ?: if (sbn.packageName == Telephony.Sms.getDefaultSmsPackage(this)) "sms" else return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || title.isEmpty()) return
        // WhatsApp re-posts old messages when a chat updates. Dedupe on the message's own
        // timestamp, so the same text sent twice still shows twice.
        val msgs = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val msgTime = (msgs?.lastOrNull() as? android.os.Bundle)?.getLong("time") ?: n.`when`
        val h = (sbn.key + title + text + msgTime).hashCode()
        if (!seen.add(h)) return
        if (seen.size > 200) seen.remove(seen.first())
        send("notification", "app" to app, "sender" to title, "text" to text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == ringingKey) {
            ringingKey = null
            send("call", "state" to "ended")
        }
    }

    private fun watchMedia(list: List<MediaController>?) {
        controllers.forEach { (c, cb) -> c.unregisterCallback(cb) }
        controllers.clear()
        list?.filter { it.packageName in mediaApps }?.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(m: MediaMetadata?) {
                    val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
                    val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                    send("media", "title" to title,
                        "artist" to (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""), "playing" to playing)
                }
            }
            c.registerCallback(cb)
            controllers += c to cb
        }
    }

    private fun send(type: String, vararg kv: Pair<String, Any>) {
        val o = JSONObject().put("type", type)
        kv.forEach { (k, v) -> o.put(k, v) }
        Board.post(this, "/event", o)
    }
}
