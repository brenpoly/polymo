package com.digitalpet.service

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.digitalpet.conversation.PetAnnouncer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PetNotificationListener : NotificationListenerService() {

    /** Injected so the listener does not have to know how the pet speaks. */
    @Inject lateinit var announcerLazy: dagger.Lazy<PetAnnouncer>
    private val announcer: PetAnnouncer? get() =
        runCatching { announcerLazy.get() }.getOrNull()

    companion object {
        private const val TAG = "PetNotificationListener"
        private var instance: PetNotificationListener? = null

        /**
         * Whether the user has granted this listener notification access.
         *
         * **One definition**, because two screens ask and they must not be able
         * to disagree: first run uses it to decide whether its step is done, and
         * the settings screen uses it to decide whether to offer the way back.
         * It reads `enabled_notification_listeners` rather than asking whether
         * we are bound, because an unbound-but-granted listener is a transient
         * state after a reinstall and is not what either caller means.
         *
         * There is no permission API behind this. Android grants it only in its
         * own settings screen, which is precisely why it goes missing quietly —
         * see `NotificationAccess.LISTENER_BODY`.
         */
        fun isEnabled(context: android.content.Context): Boolean =
            android.provider.Settings.Secure
                .getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')
                // Substring matching would let another package whose name merely
                // CONTAINS ours read as granted. Component names are
                // `pkg/cls`, so the package is what sits before the slash.
                ?.any { it.substringBefore('/') == context.packageName } == true

        /**
         * Active notifications worth telling the owner about.
         *
         * Drops two categories:
         *  - **Persistent chrome** — ongoing / no-clear / foreground-service
         *    notifications (media players, downloads, VPN, our own service).
         *  - **Silent ones** — anything whose channel importance is below
         *    DEFAULT. Android treats those as no-sound, no-heads-up background
         *    noise, and they dominated the list without being worth reporting.
         */
        fun getActiveNotificationsFiltered(): List<StatusBarNotification> {
            val listener = instance ?: return emptyList()
            try {
                val active = listener.activeNotifications ?: return emptyList()
                val ranking = NotificationListenerService.Ranking()
                val rankingMap = listener.currentRanking

                return active.filter { sbn ->
                    val notification = sbn.notification
                    val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
                    val isNoClear = (notification.flags and Notification.FLAG_NO_CLEAR) != 0
                    val isForegroundService = (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
                    if (isOngoing || isNoClear || isForegroundService) return@filter false

                    // The group SUMMARY duplicates its children: Gmail posts one
                    // per conversation plus a summary, so counting both reports
                    // roughly twice what the phone is showing. The same fault
                    // the spoken announcement had.
                    val isSummary =
                        (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
                    if (isSummary) return@filter false

                    // Ranking is per-notification and may be missing; if we cannot
                    // determine importance, keep the notification rather than
                    // silently hiding something that might matter.
                    if (rankingMap?.getRanking(sbn.key, ranking) == true) {
                        if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) {
                            return@filter false
                        }
                    }
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get active notifications", e)
                return emptyList()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected")
        if (instance == this) {
            instance = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        /*
         * The COUNT is still pulled on demand — that is what the conversation
         * engine uses. This hook exists for the pet's spoken announcement,
         * which needs the ARRIVAL rather than the total: "one new notification
         * from Gmail" is an event, and a poll cannot tell you which app.
         *
         * Nothing is decided here. This service runs in a system-bound process
         * with no view of the pet, so it hands the app label over and
         * PetAnnouncer applies the policy — see PetAnnouncement for the rules.
         */
        val n = sbn.notification ?: return
        val flags = n.flags
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (flags and Notification.FLAG_NO_CLEAR) != 0 ||
            (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        ) {
            return   // the same chrome the count filter drops
        }
        if (sbn.packageName == packageName) {
            return   // our own foreground notification, which would be a loop
        }
        /*
         * DROP THE GROUP SUMMARY. Android posts a summary alongside the child
         * for grouped apps — Gmail does — so one visible email arrives here as
         * TWO StatusBarNotifications. Counting both is how the pet announced
         * two when the phone was showing one.
         */
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }
        announcer?.onArrived(appLabel(sbn.packageName), sbn.key ?: "")
    }

    /** The user-visible name, falling back to the package when there is none —
     *  PetAnnouncement drops a blank label rather than saying "from ". */
    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        ""
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not used
    }
}
