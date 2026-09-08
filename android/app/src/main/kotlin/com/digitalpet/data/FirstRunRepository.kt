package com.digitalpet.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether setup has been walked through — the **one** thing first run remembers.
 *
 * **Everything else about the flow is derived from the world**, see `FirstRun`.
 * A stored step index is an assertion that stops being true the moment somebody
 * unpairs a pet, and it would step them past something they no longer pass. What
 * genuinely cannot be observed is whether a person has *already been offered*
 * the tour, so that is what is written down and nothing more.
 *
 * **It records being finished, not being completed**, and the difference is
 * DESIGN.md §5.2's: "a run that stops at step 2 has still achieved something".
 * Leaving halfway sets this too. The app does not need the flow to say what is
 * missing — the two chips and the care card already report every one of these
 * states in the place that fixes it, which is why abandoning setup is a
 * reasonable thing to do rather than a state to be rescued from.
 *
 * Its own preferences file, following the per-area convention already here
 * (`digital_pet_prefs` for the link, `appearance_prefs` for the theme) rather
 * than adding a key to somebody else's.
 */
@Singleton
class FirstRunRepository @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _finished = MutableStateFlow(prefs.getBoolean(KEY_FINISHED, false))

    /** False on a fresh install, and exactly once per install. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    /**
     * Persist first, then publish — the same order and the same reason as
     * `AppearanceRepository`.
     *
     * If the process dies between the two, a stored value the screen has not
     * caught up with is setup that stays finished on the next launch, whereas a
     * published value that was never stored is a tour that reappears after a
     * crash. Of the two failures, being shown the welcome screen again is by far
     * the more insulting.
     */
    fun markFinished() {
        prefs.edit().putBoolean(KEY_FINISHED, true).apply()
        _finished.value = true
    }

    /**
     * For getting back to it deliberately, and for testing on a device without
     * clearing app data — which would take the paired pet and the models with
     * it, and those are the slowest things to set up again.
     */
    fun reset() {
        prefs.edit().putBoolean(KEY_FINISHED, false).apply()
        _finished.value = false
    }

    private companion object {
        const val PREFS = "first_run_prefs"
        const val KEY_FINISHED = "finished"
    }
}
