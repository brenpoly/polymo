package com.digitalpet.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class AppUsageRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val prefs = context.getSharedPreferences("app_usage_prefs", Context.MODE_PRIVATE)

    private val _usageAlerts = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val usageAlerts: SharedFlow<String> = _usageAlerts.asSharedFlow()

    // Map of packageName to threshold in milliseconds
    private val _monitoredApps = MutableStateFlow<Map<String, Long>>(emptyMap())
    val monitoredApps: StateFlow<Map<String, Long>> = _monitoredApps.asStateFlow()

    init {
        // Load persisted monitored apps on startup
        _monitoredApps.value = loadFromPrefs()
    }

    fun getMonitoredApps(): Map<String, Long> {
        return _monitoredApps.value
    }

    fun addMonitoredApp(packageName: String, thresholdMs: Long = 5 * 60 * 1000L) {
        val current = _monitoredApps.value.toMutableMap()
        current[packageName] = thresholdMs
        _monitoredApps.value = current
        saveToPrefs(current)
    }

    fun updateMonitoredAppThreshold(packageName: String, thresholdMs: Long) {
        val current = _monitoredApps.value.toMutableMap()
        if (current.containsKey(packageName)) {
            current[packageName] = thresholdMs
            _monitoredApps.value = current
            saveToPrefs(current)
        }
    }

    fun removeMonitoredApp(packageName: String) {
        val current = _monitoredApps.value.toMutableMap()
        current.remove(packageName)
        _monitoredApps.value = current
        saveToPrefs(current)
    }

    fun sendUsageAlert(alertMessage: String) {
        _usageAlerts.tryEmit(alertMessage)
    }

    /**
     * Whether the user has granted usage access.
     *
     * **Asked live, never cached.** The only way to grant it is to leave for
     * system settings and come back, so a remembered answer is wrong at exactly
     * the moment somebody looks at it — the same reasoning as
     * `PetBleRepository.isBluetoothOn` and the notification-access check on the
     * main surface.
     *
     * It is not an ordinary permission and cannot be tested with
     * `checkSelfPermission`: `PACKAGE_USAGE_STATS` is an appop, and the manifest
     * entry only makes the app eligible to be granted it by hand.
     */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The last foreground transition seen for each monitored package.
     *
     * **The one place foreground events are read**, as of 2026-08-09. It used to
     * live inline in `PetForegroundService.pollUsageStats` and be the *only*
     * consumer; the screen-time page now needs the same answer, and two scans
     * with two windows would be two answers to "how long have you been in this
     * app".
     *
     * ### The window is midnight, and it used to be one hour
     *
     * **That hour was a real bug.** `queryEvents` only returns events inside its
     * range, so a sitting longer than the window lost its `ACTIVITY_RESUMED`
     * entirely — the package vanished from this map, `ScreenTime.overusingPackage`
     * returned null, and **the pet recovered after an hour of unbroken use.** The
     * mechanic gave up on precisely the behaviour it exists to catch, silently,
     * and nothing on either surface said so.
     *
     * Midnight is the boundary because it is the one this class already uses for
     * [usageTodayMs], so there is a single answer to "when does the day start"
     * rather than two that nearly agree. A sitting spanning midnight loses its
     * start and reads as fresh, which is a real edge and the least bad one: the
     * alternative is an unbounded query.
     *
     * Empty when access has not been granted — the platform returns nothing
     * rather than throwing, which is why [hasUsageAccess] is asked separately.
     */
    fun latestTransitions(monitored: Set<String>): Map<String, ScreenTime.Transition> =
        transitionsToday(monitored).mapValues { (_, l) -> l.maxBy { it.timestamp } }

    /**
     * **Every** foreground transition today, per monitored package, in order.
     *
     * [latestTransitions] keeps only the last of these and is what the pet's
     * health turns on. This keeps the lot, because the screen-time row shows the most
     * recent *session* and that needs the whole day's events, not just the last
     * transition — see `ScreenTime.longestSitting` for why the live sitting is
     * unshowable.
     *
     * One scan feeds both, so the number on the row and the number the pet is
     * judged by can never come from different readings of the day.
     */
    fun transitionsToday(monitored: Set<String>): Map<String, List<ScreenTime.Transition>> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return emptyMap()
        if (monitored.isEmpty()) return emptyMap()

        val events = runCatching {
            usm.queryEvents(startOfToday(), System.currentTimeMillis())
        }.getOrNull() ?: return emptyMap()

        val event = android.app.usage.UsageEvents.Event()
        val all = mutableMapOf<String, MutableList<ScreenTime.Transition>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName !in monitored) continue
            val resumed = when (event.eventType) {
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> true
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED -> false
                else -> continue
            }
            all.getOrPut(event.packageName) { mutableListOf() }
                .add(ScreenTime.Transition(resumed, event.timeStamp))
        }
        return all
    }

    /**
     * How long each monitored app has been open **in this sitting**, or 0.
     *
     * This is the number the allowance governs and the number the pet's health
     * hangs on — `ScreenTime.overusingPackage` compares exactly this against the
     * threshold. As of 2026-08-09 it is also the number the screen-time page
     * shows, which it was not before: see [usageTodayMs].
     */
    fun sittingMs(monitored: Set<String>): Map<String, Long> =
        ScreenTime.sittings(latestTransitions(monitored), monitored, System.currentTimeMillis())

    /**
     * The most recent session in each monitored app — what the screen-time row
     * shows. Live while the app is open, holding its last value once it is
     * closed. See `ScreenTime.latestSitting`.
     */
    fun latestSittingMs(monitored: Set<String>): Map<String, Long> =
        ScreenTime.latestSittings(
            transitionsToday(monitored), monitored, System.currentTimeMillis()
        )

    /**
     * How long each package has been in the foreground since midnight.
     *
     * **A different quantity from the one the pet's health uses**, and keeping
     * them apart is the whole reason `ScreenTimeDisplay` exists: the simulation
     * asks "how long has this sitting lasted", which is recoverable by closing
     * the app, while this is the day's total, which only ever grows.
     *
     * **It is stated as a fact and never compared to the allowance.** That
     * sentence was in this comment and was false for two days: from 2026-08-07
     * the screen-time row divided this by the per-sitting allowance and turned
     * the bar red at the quotient, so the bar filled within one sitting's worth
     * of use and stayed red until midnight with nothing able to reset it — while
     * the pet, which is governed by the sitting, was often perfectly well. See
     * [sittingMs], which is what that row shows now.
     *
     * `queryAndAggregateUsageStats` rather than `queryUsageStats`, because the
     * latter can return several buckets for one package and summing them
     * double-counts. Empty when access has not been granted — the platform
     * returns nothing rather than throwing, which is why [hasUsageAccess] is
     * asked separately instead of inferring it from an empty result.
     */
    fun usageTodayMs(): Map<String, Long> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return emptyMap()
        return runCatching {
            usm.queryAndAggregateUsageStats(startOfToday(), System.currentTimeMillis())
                .mapValues { (_, stats) -> stats.totalTimeInForeground }
        }.getOrDefault(emptyMap())
    }

    /**
     * Midnight this morning, and **the only definition of it in this class.**
     *
     * Both reads above take their lower bound from here so that "today" cannot
     * mean two things one line apart — which is exactly how the sitting and the
     * day came to be measured over different windows in the first place.
     */
    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // --- Persistence helpers ---

    private fun saveToPrefs(apps: Map<String, Long>) {
        val json = JSONObject()
        for ((pkg, threshold) in apps) {
            json.put(pkg, threshold)
        }
        prefs.edit().putString("monitored_apps", json.toString()).apply()
    }

    private fun loadFromPrefs(): Map<String, Long> {
        val jsonStr = prefs.getString("monitored_apps", null) ?: return emptyMap()
        return try {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, Long>()
            for (key in json.keys()) {
                map[key] = json.getLong(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
