package com.digitalpet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.digitalpet.pet.PetFace
import com.digitalpet.pet.PetFaceSets
import com.digitalpet.pet.PetReadiness
import com.digitalpet.pet.PetStatusText
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import androidx.core.app.ServiceCompat
import com.digitalpet.data.ScreenTime
import com.digitalpet.llm.LlmManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PetForegroundService : Service() {

    @Inject
    lateinit var llmManager: LlmManager

    @Inject
    lateinit var petVoice: com.digitalpet.conversation.PetVoice

    @Inject
    lateinit var appUsageRepository: com.digitalpet.data.AppUsageRepository

    /**
     * The link, which owns this service rather than the other way round.
     *
     * This used to read "the service is the always-on component, so connection
     * lifetime is tied to it". That is now exactly backwards: PetBleRepository
     * starts this service when the pet connects, and this service only ever
     * *drops* the link (see stopPet). Do not add a connect call here — it makes
     * a loop that reconnects the pet the user has just switched off.
     */
    @Inject
    lateinit var petBle: com.digitalpet.ble.PetBleRepository

    /**
     * Injected purely so that it EXISTS.
     *
     * The engine subscribes to the pet's transcripts in its own init block, and
     * that subscription is what turns speech into a reply. Nothing else in a
     * screenless process would construct it, and Hilt builds @Singletons lazily —
     * so without this field the pet would keep listening and transcribing and
     * still never answer, which is the bug this whole arrangement fixes.
     *
     * Do not "clean up" as an unused field. See PetConversationEngine.
     */
    @Inject
    lateinit var conversation: com.digitalpet.conversation.PetConversationEngine

    /**
     * For [ModelRepository.readiness]. Injecting it here also guarantees the
     * restores are kicked off when the service starts on its own — which is the
     * whole case this addresses: a pet reconnecting after being out of range
     * brings the service up with the app never opened.
     */
    @Inject
    lateinit var models: com.digitalpet.data.ModelRepository

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    /**
     * Set the instant a stop begins, so nothing re-posts the notification after
     * it has been taken away.
     *
     * **The teardown is not atomic, and that was the bug.** stopPet() drops the
     * link, which changes petBle.state, which wakes the collector in
     * startNotificationUpdates — on another dispatcher. That collector then
     * called nm.notify() *after* stopForeground(STOP_FOREGROUND_REMOVE) had
     * already run, re-posting the notification as an ordinary one that nothing
     * was left to remove. Measured: the stop logged at 22:50:27.874 and the
     * notification came back at 22:50:27.885, eleven milliseconds later.
     *
     * Cancelling the scope is not sufficient on its own — cancellation is
     * cooperative, so a collect already past its last suspension point runs to
     * completion and notifies anyway. Hence a flag as well as a cancel.
     */
    @Volatile private var stopping = false
    private val warnedSessions = mutableMapOf<String, Long>()

    private val binder = LocalBinder()
    /*
     * v2, and the rename is load-bearing rather than cosmetic.
     *
     * NOTIFICATION CHANNELS ARE IMMUTABLE ONCE CREATED. createNotificationChannel
     * on an existing id is a no-op for everything except the name and
     * description, so raising the importance of "pet_service" in code would have
     * changed nothing on any phone that had already run the app — it would look
     * fixed in the source and stay broken on the device. A new id is the only
     * way to ship a different importance to an existing install.
     */
    private val channelId = "pet_status_v2"
    private val legacyChannelId = "pet_service"
    private val notificationId = 1

    companion object {

        /**
         * The avatar's alpha when nothing has been heard from the pet — 45%,
         * from the design system's third notification specimen.
         *
         * Named rather than inlined because it is the one number in this file
         * that came from a mock rather than a measurement, and 115 is not a
         * value anybody would recognise as 45% of 255 six months from now.
         */
        private const val AVATAR_ALPHA_UNKNOWN = 115

        private const val TAG = "PetForegroundService"

        /**
         * Switch the pet off: disconnect the hardware, stop answering, and take
         * this service down.
         *
         * There was previously no way for a user to stop the pet at all. Closing
         * the app only destroys an Activity, and dismissing a foreground
         * service's notification does not stop the service — so the pet went on
         * listening through its own microphone and answering, with the one
         * visible sign of it gone and nothing left to switch off short of "Force
         * stop" in system settings. An always-on device with a microphone needs
         * an off switch that is reachable from where the user already is.
         */
        const val ACTION_STOP_PET = "com.digitalpet.action.STOP_PET"

        /** Started by [BootReceiver] after the phone restarts. */
        const val ACTION_BOOT = "com.digitalpet.action.BOOT"

        /**
         * Put the notification back after `POST_NOTIFICATIONS` is granted.
         *
         * **Granting the permission does not bring the notification back on its
         * own**, and that is not obvious. The collector below posts only when
         * the RENDERED text changes, which is right — it is what stops the shade
         * being rewritten every time a flow emits — but a permission grant
         * changes no text at all. So the notification stayed absent until the
         * pet's condition happened to move, which meant tapping *Turn
         * notifications on* appeared to do nothing: the exact symptom the button
         * exists to fix, reproduced by the fix. Measured on device 2026-08-27 —
         * the permission went green and the shade stayed empty.
         */
        const val ACTION_RENOTIFY = "com.digitalpet.action.RENOTIFY"

        /**
         * How long a boot-started service waits for the pet before giving up.
         *
         * A foreground service that never finds anything is worse than not
         * starting: it holds the process, keeps a radio retrying and leaves a
         * permanent notification about a pet that is switched off or in another
         * building. Three minutes is long enough for a pet that is charging in
         * the next room and short enough not to sit there all day.
         *
         * Only the boot path times out. A user who opened the app and is waiting
         * for their pet has said what they want.
         */
        const val BOOT_GIVE_UP_MS = 3 * 60 * 1000L

        /**
         * How much longer a handshake already in flight may take before the
         * boot give-up counts it as a failure.
         *
         * A cold boot to a connected, reporting pet has been measured at 4.4 s
         * end to end, so 20 s is generous by a factor of four — it is a
         * backstop against a link that has hung, not a budget anybody should be
         * spending. See startAfterBoot.
         */
        const val BOOT_HANDSHAKE_GRACE_MS = 20 * 1000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): PetForegroundService = this@PetForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startUsagePolling()
        startNotificationUpdates()
        // The pet reacting to its own life — fed, played with, calling out.
        // Idempotent, because this service can be restarted and a second
        // collector would make the pet say everything twice.
        petVoice.start(serviceScope)
        // Nothing here opens the BLE link. This service is started *because* the
        // pet connected — see PetBleRepository.startPetService — so reaching back
        // to connect would be a loop, and would reconnect a pet the user had just
        // switched off by stopping this service.
    }

    override fun onDestroy() {
        super.onDestroy()
        // The engine is a process-lifetime @Singleton and outlives this service,
        // so it has to be told. Without this it would keep answering after the
        // service went away — which is the whole shape of the bug this fixes.
        conversation.setActive(false)
        petBle.disconnect()
        serviceJob.cancel()
    }

    /**
     * Screen-time monitoring, deliberately scoped to this service.
     *
     * It runs on serviceScope, which onDestroy cancels, so it starts when the pet
     * connects and stops when the pet is switched off — the same lifetime as
     * everything else here. That is a requirement, not an accident of where the
     * code happens to sit: the nudges are delivered *by the pet*, so polling
     * usage while no pet is connected would be collecting the user's app activity
     * to no purpose, and would keep doing it after they had switched the pet off.
     *
     * Do not move this to a process-lifetime singleton or its own worker.
     * PetConversationEngine gates the resulting alerts on the same active flag,
     * so both halves stay tied to the pet being present.
     */
    /**
     * Reconnect after a reboot.
     *
     * **This is the one place the service reaches back to open the link**, and
     * it is a deliberate exception to the rule that the connection drives the
     * service and never the reverse. That rule exists because the service is
     * normally started *because* the pet connected, so calling
     * `connectRemembered()` from it would reconnect a pet the user had just
     * switched off. Neither half of that applies here: after a reboot there is
     * no link to have started us, and no "just switched off" to undo — the stop
     * is deliberately not persisted, so it cannot survive a restart anyway.
     *
     * A receiver cannot do this itself. `onReceive` returns immediately and its
     * process becomes killable, while connecting is asynchronous and takes
     * seconds; something has to hold the process open, and a foreground service
     * is the only honest way to do that.
     */
    private fun startAfterBoot() {
        Log.i(TAG, "boot: reconnecting to the remembered pet")
        petBle.connectRemembered()

        serviceScope.launch {
            delay(BOOT_GIVE_UP_MS)

            /*
             * A HANDSHAKE IN PROGRESS IS NOT AN ABSENT PET.
             *
             * This used to test `state != READY` and stand down on the spot,
             * which drops a pet that turned up in the last seconds of the
             * window: connecting is not the end of it — MTU negotiation,
             * service discovery and the first reads all have to finish before
             * the link is READY, and that takes seconds.
             *
             * Caught on a real reboot on 2026-08-11, by accident, because the
             * pet was powered on part way through the test:
             *
             *   21:37:00  connected; requesting MTU
             *   21:37:05  boot: no pet after 180s, standing down
             *
             * Five seconds. Nothing was wrong with the pet, the link or the
             * timer's duration — only with reading "not finished yet" as "not
             * there". It needed a pet appearing inside a five-second window
             * three minutes after a reboot to show itself, which is why the
             * deliberate version of this test never found it.
             */
            if (BootOutcome.of(petBle.state.value) == BootOutcome.WAIT) {
                Log.i(TAG, "boot: window is up but a handshake is in flight, waiting")
                kotlinx.coroutines.withTimeoutOrNull(BOOT_HANDSHAKE_GRACE_MS) {
                    petBle.state.first { BootOutcome.of(it) != BootOutcome.WAIT }
                }
            }

            if (BootOutcome.of(petBle.state.value) != BootOutcome.KEEP) {
                // Nothing found. Stop rather than leave a notification about a
                // pet that is not there and a radio retrying into an empty room.
                Log.i(TAG, "boot: no pet after ${BOOT_GIVE_UP_MS / 1000}s, standing down")
                /*
                 * NOT stopByUser(), and this is the second half of the same bug.
                 *
                 * Standing down used to route through stopPet(), which persists
                 * `stoppedByUser` and logs "switched off by the user" — about a
                 * user who did nothing of the kind. Had it stuck, a pet that was
                 * merely out of range at boot would stay off until somebody
                 * pressed "Turn it back on", under a card telling them they had
                 * switched it off themselves.
                 *
                 * It did NOT stick, and that is worse rather than reassuring:
                 * `apply()` is asynchronous and `stopSelf()` killed the process
                 * before it flushed. The behaviour was correct by a race.
                 */
                standDown()
            }
        }
    }

    private fun startUsagePolling() {
        serviceScope.launch {
            while (true) {
                // Logged every tick so that "does screen-time monitoring stop
                // when the pet is off?" is answerable by looking, rather than by
                // trusting that the coroutine scope really was cancelled.
                android.util.Log.i(TAG, "usage poll tick")
                pollUsageStats()
                delay(60_000L) // Poll every minute
            }
        }
    }

    /**
     * One poll: work out whether the user is over a threshold, tell the pet,
     * and nudge once per session.
     *
     * The pet is told EVERY poll, and that is the phase 4 change. Screen time
     * is a sensor into the simulation (DESIGN.md §1 decision 3), so the pet
     * falling ill is the message and the nudge is the garnish. The report is a
     * level rather than an edge because the pet stops believing a stale one —
     * see PetProtocol.ScreenTime.
     */
    private fun pollUsageStats() {
        val monitoredApps = appUsageRepository.getMonitoredApps()
        if (monitoredApps.isEmpty()) {
            // Nothing is watched, so the user cannot be overusing anything.
            // Said out loud rather than by returning early: a pet left ill when
            // the last monitored app was removed would have no way back except
            // the firmware's stale timeout, minutes later.
            petBle.sendScreenTime(false)
            return
        }

        /*
         * THE SCAN MOVED TO AppUsageRepository, 2026-08-09.
         *
         * It used to be inline here with a ONE-HOUR lookback, and that hour was
         * a bug: `queryEvents` returns nothing outside its range, so a sitting
         * longer than the window lost its ACTIVITY_RESUMED, the package fell out
         * of the map, and this method reported the user as not overusing —
         * **the pet recovered after an hour of unbroken use**, which is the one
         * case the mechanic exists for. Silently, once a minute.
         *
         * It also has a second consumer now: the screen-time page shows the same
         * sitting this decides on, and two scans over two windows would be two
         * answers to the same question.
         */
        val latest = appUsageRepository.latestTransitions(monitoredApps.keys)
        val now = System.currentTimeMillis()
        val overusing = ScreenTime.overusingPackage(latest, monitoredApps, now)

        // The alert goes FIRST, and the order is deliberate.
        //
        // It no longer starts a reply — it arms one, and the pet reporting that
        // it has fallen ill is what fires it (see PetConversationEngine). So the
        // arming has to be under way before the report that can cause the
        // illness. Reversed, the pet's "I am sick" could in principle overtake a
        // nudge that had not been armed yet, and the nudge would be dropped.
        //
        // The margin is comfortable rather than enforced: this is a coroutine
        // dispatch in-process against a BLE write, the pet's tick, and a
        // notification back — measured at 85 ms. Ordering it this way costs
        // nothing and removes the only direction the race can go.
        //
        // Still edge-triggered, once per session: a sassy line every minute
        // would be nagging, and the part that now repeats is the drain the pet
        // is actually suffering.
        if (overusing != null) {
            val resumedAt = latest.getValue(overusing).timestamp
            if ((warnedSessions[overusing] ?: 0L) < resumedAt) {
                warnedSessions[overusing] = now
                appUsageRepository.sendUsageAlert(overusing)
            }
        }

        // The pet decides what this means for it — whether to fall ill, how
        // fast to decline, when to stop believing the report. None of that is
        // decided here, which is DESIGN.md §1 decision 1.
        petBle.sendScreenTime(overusing != null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_BOOT) {
            startAfterBoot()
        }
        if (intent?.action == ACTION_STOP_PET) {
            stopPet()
            // NOT sticky: the user asked for this to be off, so it must not come
            // back when the system next revives the process.
            return START_NOT_STICKY
        }

        /*
         * A RENOTIFY IS NOT A REAL START. It falls through to the
         * startForeground below — which is what actually re-posts — but it must
         * not clear `stopping`, or granting notifications would resurrect a pet
         * the user had deliberately switched off, in the shade, without asking.
         */
        if (intent?.action == ACTION_RENOTIFY && stopping) return START_STICKY

        // A real start clears the stop, or the notification would stay silenced
        // for the rest of the process after one switch-off.
        stopping = false
        val notification = createNotification()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    notificationId,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // Must match android:foregroundServiceType in the
                        // manifest or startForeground throws at runtime — the
                        // two are checked against each other, and a mismatch is
                        // not a build error.
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    } else {
                        0
                    }
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: SecurityException) {
            // Permission denied or missing
        }

        // This service exists to serve a connected pet, so PetBleRepository
        // starts it when the link comes up rather than the service reaching back
        // to open the link itself. Doing both would be a loop: the service would
        // reconnect the pet the user had just switched off by stopping it.
        conversation.setActive(true)

        return START_STICKY
    }

    /**
     * Everything needed for "off" to mean off.
     *
     * Order matters: stop answering before dropping the link, so a reply already
     * being spoken is cancelled rather than left playing out of the pet's speaker
     * after the user asked it to stop.
     */
    /**
     * Stop because nothing was found, NOT because anybody asked.
     *
     * Everything [stopPet] does except the persisted user-stop: the link is
     * dropped with [PetBleRepository.disconnect], so opening the app or the pet
     * coming back into range reconnects normally. That is the difference the
     * whole off switch rests on — a dropped link and a deliberate stop are
     * different facts, and only one of them is the user's.
     */
    private fun standDown() = stopPet(userAsked = false)

    private fun stopPet(userAsked: Boolean = true) {
        /*
         * ORDER MATTERS, and the first two lines are new.
         *
         * Stop the background work BEFORE dropping the link, because dropping
         * the link is what wakes the notification collector. Doing it the other
         * way round leaves a race that the notification wins.
         *
         * cancelChildren rather than cancel: the job is reused if the service is
         * started again, and cancelling it outright would leave a dead scope
         * that silently runs nothing — which is the failure shape this codebase
         * keeps producing.
         */
        stopping = true
        serviceJob.cancelChildren()
        conversation.setActive(false)
        /*
         * stopByUser(), not disconnect(): the stop is PERSISTED, so no automatic
         * path can undo it. disconnect() only cleared process state, which
         * MainActivity.onStart handed straight back on the next foreground —
         * swiping the notification away disconnected the pet and then went
         * looking for it again seconds later. The way back on is an explicit
         * Reconnect; see PetBleRepository.reconnectByUser.
         */
        if (userAsked) petBle.stopByUser() else petBle.disconnect()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Belt and braces: STOP_FOREGROUND_REMOVE only removes the notification
        // in its foreground role. Anything that re-posted it by hand survives
        // that, so cancel it by id as well.
        getSystemService(NotificationManager::class.java)?.cancel(notificationId)
        stopSelf()
    }

    /**
     * Fires both from the notification's Stop button and from the user swiping
     * the notification away.
     *
     * Treating a dismissal as "stop" is the point: since Android 13 a foreground
     * service's notification can be swiped away while the service keeps running,
     * which is exactly how the pet ended up running invisibly. Either the
     * notification is there, or the pet is off.
     */
    private fun stopPetPendingIntent(): android.app.PendingIntent =
        android.app.PendingIntent.getService(
            this,
            0,
            Intent(this, PetForegroundService::class.java).setAction(ACTION_STOP_PET),
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

    /**
     * Tapping the notification opens the app. It previously did nothing.
     *
     * **The combination was the problem, not the omission.** The notification
     * had a Stop action and a delete intent, so the one gesture everybody tries
     * first — tapping the body — was inert, while the gesture people reach for
     * when something seems inert — swiping it away — *stops the pet*. A control
     * that ignores you and a neighbouring one that is destructive is a bad pair
     * to leave in the one notification that is on screen permanently.
     *
     * **Uses the launcher intent rather than an explicit `MainActivity` one**, so
     * it behaves exactly like tapping the app icon: it resumes the existing task
     * if the app is already open, instead of starting a second copy on top of it.
     * An explicit `Intent(this, MainActivity::class.java)` with `NEW_TASK` does
     * *not* do that — `MainActivity` has the default launch mode, so it would be
     * recreated and the back stack would grow every time somebody tapped.
     *
     * Worth knowing: `MainActivity.onStart` calls `connectRemembered()`, so
     * tapping this also retries a dropped link. That is a side effect rather
     * than the reason, but it makes the tap useful in exactly the state where
     * someone is most likely to try it.
     *
     * Request code 1, not 0: `stopPetPendingIntent` holds 0, and two
     * PendingIntents that differ only in their target can collide under
     * `FLAG_UPDATE_CURRENT` when they share one.
     */
    private fun openAppPendingIntent(): android.app.PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, com.digitalpet.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        return android.app.PendingIntent.getActivity(
            this,
            1,
            launch,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Your pet"
            val descriptionText = "How your pet is, and the link to it"
            /*
             * DEFAULT rather than LOW, and this is the whole fix for "the
             * notification never appears on the lock screen".
             *
             * Anything below IMPORTANCE_DEFAULT is classed as *silent*, and the
             * stock lock-screen setting is "Hide silent conversations and
             * notifications" — so a LOW notification is filtered off the lock
             * screen entirely and only turns up in the Silent section once the
             * phone is unlocked. All the VISIBILITY_PRIVATE and publicVersion
             * work was correct and simply never reachable, because the thing it
             * governs is what to show on a lock screen this never reached.
             *
             * DEFAULT does not mean intrusive: heads-up needs IMPORTANCE_HIGH,
             * and the sound and vibration are turned off below. What it buys is
             * being allowed on the lock screen at all — which for a pet whose
             * whole mechanic is asking for attention is the point of having a
             * notification.
             */
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
                // Silent by configuration rather than by importance. A permanent
                // notification that pings every time the pet gets hungry would
                // be intolerable, and setOnlyAlertOnce alone does not cover the
                // first post.
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                // No badge dot: this notification is always present, so a dot on
                // the launcher icon would be permanently lit and mean nothing.
                setShowBadge(false)
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            // Tidy the old low-importance channel away so it stops appearing as
            // a dead entry in the app's notification settings.
            notificationManager.deleteNotificationChannel(legacyChannelId)
        }
    }

    private fun createNotification(): Notification = buildNotification(discreet = false)

    /**
     * @param discreet the lock-screen version. Only the *reason* for sickness is
     *   held back — see [PetStatusText.headline]. Everything else is identical,
     *   because a pet asking for food is not a secret.
     */
    private fun buildNotification(discreet: Boolean): Notification {
        val condition = petBle.condition.value
        val state = petBle.state.value
        val shown = condition.takeIf { !PetStatusText.readingsAreStale(state) }

        /*
         * The face set the pet says it is wearing, falling back to the default.
         *
         * The fallback is the same set the firmware falls back to when NVS names
         * one it does not have, so an unknown answer and a silent pet both draw
         * the face the pet is most likely actually wearing rather than a guess.
         */
        val faceSet = PetFaceSets.byId(petBle.faceSetId.value) ?: PetFaceSets.default

        /*
         * READINESS OUTRANKS THE PET'S CONDITION. A hungry pet is something to
         * act on; a pet that cannot answer at all is something to act on FIRST,
         * and it is the case with no phone screen in it — this service starts
         * itself when a pet reconnects after being out of range, so the app's
         * readiness banner is unreachable exactly when it would be needed.
         */
        val readiness = models.readiness.value
        val notReady = PetReadiness.headline(readiness)

        val remoteViews = android.widget.RemoteViews(
            packageName, com.digitalpet.R.layout.notification_pet
        )
        remoteViews.setTextViewText(
            com.digitalpet.R.id.notification_title,
            notReady ?: PetStatusText.headline(shown, discreet = discreet)
        )
        remoteViews.setTextViewText(
            com.digitalpet.R.id.notification_text,
            PetReadiness.action(readiness)
                ?: PetStatusText.notificationSubtitle(
                    shown, state, petBle.pairedAddress.value != null
                )
        )

        /*
         * THE FACE FOLLOWS THE PET'S MOOD, as of 2026-08-09.
         *
         * `PetFace` mirrors the firmware's own rule, so this is the face the pet
         * is actually wearing rather than one picture standing in for all of
         * them — see there for why the phone can derive it and which transient
         * expressions it deliberately does not try to.
         *
         * One drawable per face AND PER SET, because RemoteViews cannot draw:
         * it can only be handed a resource id. Every one of them is generated
         * from the same numbers `PetPanel` scales and the firmware draws, so
         * the notification cannot drift from the pet on the shelf.
         */
        remoteViews.setImageViewResource(
            com.digitalpet.R.id.notification_avatar,
            faceSet.notificationIcon(PetFace.of(shown)),
        )

        /*
         * THE FACE DIMS WHEN THE READINGS ARE NOT KNOWN — the design system's
         * third notification specimen, which draws the avatar at 45% under
         * "Waiting to hear from your pet…".
         *
         * It is §5.0 rule 2 in a picture. The text already refuses to assert
         * what we have not been told — the meters blank, the subtitle says the
         * link is down — and until now the face carried on looking exactly as
         * alert as it does when the pet is right there. A drawn pet is a claim
         * about the pet like any other.
         *
         * `shown` is already null whenever the readings are stale, so this
         * follows the same condition the text does rather than a second one
         * that could disagree with it.
         */
        remoteViews.setInt(
            com.digitalpet.R.id.notification_avatar,
            "setImageAlpha",
            if (shown == null) AVATAR_ALPHA_UNKNOWN else 255,
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // Small icon is required by system, but custom layout overrides body
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remoteViews)
            // Actions are only rendered in the *expanded* layout, and with a
            // custom view there is no expanded layout unless one is set — the
            // Stop button simply did not appear without this.
            .setCustomBigContentView(remoteViews)
            // The pre-Oreo twin of the channel importance above. LOW here has
            // the same effect on old devices that IMPORTANCE_LOW had on new
            // ones: no lock screen.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            // This notification now updates whenever the pet changes. Without
            // this every update would be treated as a new alert, and a pet
            // getting hungry would buzz the phone.
            .setOnlyAlertOnce(true)
            // The only user-reachable off switch for a device that is listening
            // through a microphone. See ACTION_STOP_PET.
            .addAction(0, PetStatusText.stopActionLabel(state), stopPetPendingIntent())
            .setDeleteIntent(stopPetPendingIntent())
            // Tapping the body opens the app. See openAppPendingIntent for why
            // the absence of this was worse than it sounds.
            .setContentIntent(openAppPendingIntent())

        if (!discreet) {
            // VISIBILITY_PRIVATE tells the system to show publicVersion on a
            // secure lock screen and this one once unlocked.
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(buildNotification(discreet = true))
        }
        return builder.build()
    }

    /**
     * Keep the notification saying what the pet is actually doing.
     *
     * RE-POSTED ONLY WHEN THE RENDERED TEXT CHANGES, not when the data does, and
     * that distinction is the whole reason this is cheap. The pet notifies its
     * condition whenever the battery moves 20 mV, which during a discharge is
     * every few minutes and during charging more often still — but "child · 42%"
     * does not change on every millivolt, so almost all of those collapse to no
     * work at all. Re-posting on every notification would light the ambient
     * display on some phones for a number nobody can see changing.
     *
     * On serviceScope, so it dies with the service exactly like the usage poll:
     * a notification about a pet that has been switched off should not exist,
     * and there is nothing to update it from anyway.
     */
    private fun startNotificationUpdates() {
        serviceScope.launch {
            var lastRendered: String? = null
            /*
             * THE FACE SET HAS TO BE A SOURCE, NOT JUST A KEY.
             *
             * It was in the dedupe key below and read with `.value` from inside
             * the collector — so the key was right and could never be
             * evaluated. Nothing woke this flow when the set changed, and the
             * shade kept the old face until something else happened to move the
             * condition. Reported from a phone: change the personality and the
             * pet and the app change together while the notification does not.
             *
             * The same shape as gotcha 2c and the sleep chain: a check that
             * cannot fire is worse than no check, because the comment beside it
             * says the case is handled. A value read inside a collector is not
             * observed — only what is combined here is.
             */
            combine(
                petBle.condition, petBle.state, models.readiness,
                petBle.faceSetId, petBle.pairedAddress,
            ) { condition, state, readiness, faceSetId, paired ->
                NotificationInputs(condition, state, readiness, faceSetId, paired != null)
            }
                .collect { (condition, state, readiness, faceSetId, paired) ->
                    val shown = condition.takeIf { !PetStatusText.readingsAreStale(state) }
                    /*
                     * THE FACE IS PART OF THE KEY, and leaving it out would have
                     * been a silent staleness bug rather than a missed nicety.
                     *
                     * This dedupes on what is RENDERED, and the face is now
                     * rendered — so it has to be in here or a mood change that
                     * does not move the text never reaches the shade. That is
                     * not hypothetical: satiety 4 / happiness 2 draws HAPPY and
                     * satiety 4 / happiness 1 draws NEUTRAL, and both say
                     * "Could use some attention." The pet's face would have gone
                     * on smiling.
                     *
                     * `shown == null` is in too, because it drives the 45% dim
                     * and a stale link does not always change the wording.
                     */
                    val rendered = PetStatusText.stopActionLabel(state) + "|" +
                        PetFace.of(shown) + "|" + (shown == null) + "|" +
                        // The set is in the key for the same reason the face is:
                        // switching personality changes the avatar without
                        // changing a word, so without this the shade would keep
                        // the old one. It comes from the COMBINE above, not from
                        // `.value` — that was the bug, see there.
                        (faceSetId ?: "") + "|" +
                        (PetReadiness.headline(readiness)
                        ?: PetStatusText.headline(shown)) + "|" +
                        (PetReadiness.action(readiness)
                            ?: PetStatusText.notificationSubtitle(
                                shown, state, paired
                            ))
                    if (rendered == lastRendered) return@collect
                    lastRendered = rendered
                    // Logged so "was the pet able to answer just then?" is
                    // answerable by looking rather than by inference. The
                    // window this closes is invisible otherwise: it opens on
                    // every service start and shuts by itself ~11 s later.
                    android.util.Log.i(TAG, "notification -> $rendered")

                    // The pet is being switched off; do not put the
                    // notification back after stopPet has removed it.
                    if (stopping) return@collect
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(notificationId, buildNotification(discreet = false))
                }
        }
    }
}

/**
 * The four things the notification is a function of.
 *
 * Kotlin has `Triple` and stops there, and the missing fourth slot is not a
 * neutral fact here: the face set was left reading `.value` from inside the
 * collector rather than being combined, and so the shade kept showing the old
 * personality. Naming the inputs makes adding a fifth a compile-time change to
 * this class instead of a decision about whether it is worth a data holder.
 */
private data class NotificationInputs(
    val condition: com.digitalpet.ble.PetProtocol.Condition?,
    val state: com.digitalpet.ble.PetBleRepository.State,
    val readiness: com.digitalpet.pet.PetReadiness,
    val faceSetId: String?,
    /**
     * Whether a pet is remembered — NOT the address, because the address is not
     * rendered and combining it would wake the updater for a change nobody can
     * see. `notificationSubtitle` asks only whether one exists.
     */
    val paired: Boolean,
)
