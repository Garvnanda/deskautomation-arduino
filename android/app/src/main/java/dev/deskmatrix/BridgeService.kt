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
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
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
    private var ringing = false
    private var callerName: String? = null
    private var callerNameAt = 0L
    private var lastBattery = -1

    /** Reliable ringing / answered / ended, needs READ_PHONE_STATE. */
    private val phone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> if (!ringing) {
                    ringing = true
                    // Only trust a name seen in the last few seconds (i.e. from this call).
                    if (System.currentTimeMillis() - callerNameAt > 5000) callerName = null
                    // Also read any dialer notification that is already showing.
                    runCatching { activeNotifications.forEach { readCaller(it) } }
                    // Give the dialer a moment to post its notification with the caller's name.
                    Handler(Looper.getMainLooper()).postDelayed({ if (ringing) sendRinging() }, 800)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> if (ringing) {
                    ringing = false
                    send("call", "state" to "answered")
                }
                TelephonyManager.EXTRA_STATE_IDLE -> if (ringing) {
                    ringing = false
                    send("call", "state" to "ended")
                }
            }
        }
    }
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
        registerReceiver(phone, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        val msm = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, BridgeService::class.java)
        msm.addOnActiveSessionsChangedListener({ watchMedia(it) }, me)
        watchMedia(msm.getActiveSessions(me))
    }

    override fun onListenerDisconnected() {
        runCatching { unregisterReceiver(battery) }
        runCatching { unregisterReceiver(phone) }
        watchMedia(null)
    }

    /**
     * Reads the caller's name from the dialer's notification. The call state itself comes from
     * the phone-state broadcast. Returns true if this was a dialer notification.
     */
    private fun readCaller(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val dialer = n.category == Notification.CATEGORY_CALL ||
            listOf("dialer", "telecom", "incallui").any { sbn.packageName.contains(it, true) }
        if (!dialer) return false
        // Skip missed-call and other non-live dialer notifications (they name old callers).
        if (n.category != Notification.CATEGORY_CALL && n.flags and Notification.FLAG_ONGOING_EVENT == 0) return true
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val person = if (Build.VERSION.SDK_INT >= 33)
            n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name?.toString()
        else null
        val name = person?.takeIf { it.isNotBlank() }
            ?: listOf(title, text).firstOrNull { it.isNotBlank() && !it.contains("call", true) }
            ?: ""
        if (name.isNotEmpty() && name != callerName) {
            // The notification can arrive before or after the ring broadcast; keep it either way.
            callerName = name
            callerNameAt = System.currentTimeMillis()
            if (ringing) sendRinging()  // name arrived after the ring started: update the board
        }
        return true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (readCaller(sbn)) return
        val n = sbn.notification
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

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

    private fun sendRinging() =
        send("call", "state" to "ringing", "name" to (callerName ?: "INCOMING"), "number" to "")

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
