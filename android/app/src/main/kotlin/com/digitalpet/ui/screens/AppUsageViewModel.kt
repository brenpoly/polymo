package com.digitalpet.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digitalpet.data.AppUsageRepository
import com.digitalpet.data.ScreenTimeDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppInfo(val packageName: String, val appName: String)

@HiltViewModel
class AppUsageViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appUsageRepository: AppUsageRepository
) : ViewModel() {

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    val monitoredApps = appUsageRepository.monitoredApps

    // UI State for "Add App" view
    private val _isAddAppView = MutableStateFlow(false)
    val isAddAppView: StateFlow<Boolean> = _isAddAppView.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedAppsToAdd = MutableStateFlow<Set<String>>(emptySet())
    val selectedAppsToAdd: StateFlow<Set<String>> = _selectedAppsToAdd.asStateFlow()

    val filteredInstalledApps: StateFlow<List<AppInfo>> = combine(
        _installedApps,
        _searchQuery,
        appUsageRepository.monitoredApps
    ) { apps, query, monitored ->
        apps.filter {
            !monitored.containsKey(it.packageName) &&
            it.appName.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Today's foreground total per package, refreshed by [refreshUsage]. */
    private val _usageToday = MutableStateFlow<Map<String, Long>>(emptyMap())
    val usageToday: StateFlow<Map<String, Long>> = _usageToday.asStateFlow()

    /** Whether usage access is granted, refreshed by [refreshUsage]. */
    private val _hasUsageAccess = MutableStateFlow(false)
    val hasUsageAccess: StateFlow<Boolean> = _hasUsageAccess.asStateFlow()

    /**
     * How long each tracked app has been open **in this sitting** — the number
     * the allowance governs, and as of 2026-08-09 the number the row shows.
     *
     * **A snapshot on appearance is the right granularity, not a limitation.**
     * You cannot be in a tracked app and looking at this screen at the same
     * time, so the interesting moment is the return: come back after closing
     * Instagram and the row reads `0 / 25 min`. That IS the reset, and watching
     * it tick would require being somewhere you cannot be.
     */
    private val _sittingMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val sittingMs: StateFlow<Map<String, Long>> = _sittingMs.asStateFlow()

    /**
     * The most recent session in each tracked app — **what the row shows.**
     * Live while the app is open, holding its last value once closed, so it
     * reflects what you just did. See `ScreenTime.latestSitting`.
     */
    private val _latestSittingMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val latestSittingMs: StateFlow<Map<String, Long>> = _latestSittingMs.asStateFlow()

    /**
     * Re-read both facts about usage.
     *
     * **Called every time the screen is shown, not once.** Granting access means
     * leaving for system settings and coming back, so the interesting moment is
     * exactly a return to this screen — and both readings have moved on by then
     * too. Cheap enough to do on every appearance: one appop check, one
     * aggregated query, and one event scan.
     */
    fun refreshUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            _hasUsageAccess.value = appUsageRepository.hasUsageAccess()
            _usageToday.value = appUsageRepository.usageTodayMs()
            val monitored = appUsageRepository.getMonitoredApps().keys
            _sittingMs.value = appUsageRepository.sittingMs(monitored)
            _latestSittingMs.value = appUsageRepository.latestSittingMs(monitored)
        }
    }

    /**
     * A readable name for a tracked package.
     *
     * Falls back to the package name, which is ugly and honest: an app can be
     * uninstalled while still being tracked, and showing its id is better than
     * dropping a row the user can still see the effects of.
     */
    fun appName(packageName: String): String =
        installedApps.value.firstOrNull { it.packageName == packageName }?.appName
            ?: runCatching {
                val pm = appContext.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)

    fun loadInstalledApps() {
        if (_installedApps.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val pm = appContext.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val apps = packages.mapNotNull { info ->
                if (pm.getLaunchIntentForPackage(info.packageName) != null) {
                    AppInfo(
                        packageName = info.packageName,
                        appName = pm.getApplicationLabel(info).toString()
                    )
                } else null
            }.sortedBy { it.appName.lowercase() }
            _installedApps.value = apps
        }
    }

    fun setIsAddAppView(isAdd: Boolean) {
        _isAddAppView.value = isAdd
        if (!isAdd) {
            _searchQuery.value = ""
            _selectedAppsToAdd.value = emptySet()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleAppSelection(packageName: String) {
        val current = _selectedAppsToAdd.value.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        _selectedAppsToAdd.value = current
    }

    /**
     * The allowance every app picked in this round will start with.
     *
     * One stepper for the whole selection rather than one per app, which is the
     * design's "Allowance for both" — anyone adding several apps at once is
     * making one decision about them, and each can be tuned afterwards on its
     * own row.
     */
    private val _pendingAllowanceMs = MutableStateFlow(ScreenTimeDisplay.DEFAULT_MS)
    val pendingAllowanceMs: StateFlow<Long> = _pendingAllowanceMs.asStateFlow()

    fun stepPendingAllowance(up: Boolean) {
        _pendingAllowanceMs.value = ScreenTimeDisplay.step(_pendingAllowanceMs.value, up)
    }

    fun confirmAddApps() {
        val allowance = _pendingAllowanceMs.value
        _selectedAppsToAdd.value.forEach { packageName ->
            appUsageRepository.addMonitoredApp(packageName, allowance)
        }
        setIsAddAppView(false)
    }

    /** Nudge one tracked app's allowance by a step, in either direction. */
    fun stepAppThreshold(packageName: String, currentMs: Long, up: Boolean) {
        appUsageRepository.updateMonitoredAppThreshold(
            packageName, ScreenTimeDisplay.step(currentMs, up)
        )
    }

    fun removeTrackedApp(packageName: String) {
        appUsageRepository.removeMonitoredApp(packageName)
    }
}
