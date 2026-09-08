package com.digitalpet.ui.theme

/**
 * Which of the two schemes to show, and whether that is our decision at all.
 *
 * The app had a light theme and a dark one and no way to ask for either: it
 * followed the system and that was the whole story. [SYSTEM] keeps that as the
 * default, because a phone-wide preference is usually the right answer and an
 * app that ignores it is being rude; the other two exist because "usually" is
 * not "always", and someone reading in bed should not have to change a system
 * setting to make one app dim.
 *
 * **The resolution is a pure function over the whole state space**, in the same
 * spirit as the firmware's `sleep_decide()` — an exhaustive `when` over an enum
 * used as an expression, so adding a fourth mode is a compile error rather than
 * a case that silently falls through to light. That property is the reason this
 * is a type and not a `Boolean?` or a string.
 */
enum class ThemeMode {

    /** Follow the phone. The default, and what the app has always done. */
    SYSTEM,

    /** Always light, whatever the phone says. */
    LIGHT,

    /** Always dark, whatever the phone says. */
    DARK;

    /**
     * @param systemInDark what the phone currently says, which only [SYSTEM] uses.
     */
    fun isDark(systemInDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDark
        LIGHT -> false
        DARK -> true
    }

    /** What to call this in a list of choices. */
    val label: String
        get() = when (this) {
            SYSTEM -> "Use system setting"
            LIGHT -> "Light"
            DARK -> "Dark"
        }

    companion object {

        /**
         * Read back what was stored, tolerating anything that is not a mode.
         *
         * Persisted by [name], so renaming a constant orphans a stored value —
         * and the fallback here is what stops that turning into a crash on a
         * device that had the old name. Falling back to [SYSTEM] rather than to
         * a fixed scheme means a corrupted preference lands on the setting the
         * user most likely wants anyway.
         */
        fun fromStored(stored: String?): ThemeMode =
            entries.firstOrNull { it.name == stored } ?: SYSTEM
    }
}
