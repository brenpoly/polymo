package com.digitalpet.data

import android.content.Context
import com.digitalpet.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one thing the user gets to say about how the app looks.
 *
 * **Read once at construction, not on every access.** The theme is consulted on
 * every recomposition of the root, and SharedPreferences would happily serve
 * that from its own memory cache — but a `StateFlow` is what makes a change
 * *arrive*, and reading a preference cannot tell the composition that it
 * changed. So the file is the record and the flow is the truth in flight, which
 * is the same split `PetBleRepository` uses for the off switch.
 *
 * A `@Singleton`, because two copies would be two answers: the root of the app
 * reads it to pick a scheme and the Appearance screen writes it, and those are
 * different composables in different back-stack entries.
 *
 * Its own preferences file, following the per-area convention already here
 * (`digital_pet_prefs` for the link, `app_usage_prefs` for allowances) rather
 * than adding a key to somebody else's.
 */
@Singleton
class AppearanceRepository @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(ThemeMode.fromStored(prefs.getString(KEY_MODE, null)))

    /** What to show. [ThemeMode.SYSTEM] until the user says otherwise. */
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /**
     * Persist first, then publish.
     *
     * That order matters if the process dies between the two: a stored value the
     * screen has not caught up with is a theme that is right on the next launch,
     * whereas a published value that was never stored is a setting that silently
     * forgets itself — and a preference that does not survive a restart reads as
     * broken rather than as unsaved.
     */
    fun setMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private companion object {
        const val PREFS = "appearance_prefs"
        const val KEY_MODE = "theme_mode"
    }
}
