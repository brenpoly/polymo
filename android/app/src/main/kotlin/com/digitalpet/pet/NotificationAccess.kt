package com.digitalpet.pet

/**
 * Getting `POST_NOTIFICATIONS` back after first run has spent its one chance.
 *
 * **The bug this exists for.** The permission was requested in exactly one
 * place — first run step 5 — and first run is finished once, forever. Skip or
 * dismiss that step and the foreground notification is gone permanently: the
 * service still runs, still builds the notification, still posts it, and
 * Android drops it **with no exception and no log**. Nothing on any screen
 * admitted it. Found on a device on 2026-08-27, where the permission had never
 * been answered at all — `USER_SET` was absent while the two permissions that
 * had been asked for both carried it.
 *
 * **Why this is not just "ask again".** Android gives no way to ask whether a
 * request will actually show a dialog. `shouldShowRequestPermissionRationale`
 * is false in **two opposite cases** — never asked, and permanently denied —
 * so a row that only ever calls `launch()` gives you a button that does
 * visibly nothing on the very device that needs it most. That is worse than no
 * button: it teaches that the fix does not work.
 *
 * **The escalation resolves the ambiguity by trying.** Ask once; if the answer
 * comes back denied and there is still no rationale to show, the dialog did not
 * appear and will not, so the only route left is the system settings screen.
 * That needs no persisted flag and self-corrects on whichever device it is —
 * see [next].
 */
object NotificationAccess {

    /** What tapping the row should do, given where the permission stands. */
    enum class Next {
        /** Nothing to do; the row is not drawn at all. */
        NOTHING,

        /** A dialog will appear. */
        ASK,

        /** A dialog will not appear again; only system settings can grant it. */
        OPEN_SETTINGS,
    }

    /**
     * @param granted  the permission is held.
     * @param rationale `shouldShowRequestPermissionRationale` — true only after
     *   the user has actively declined at least once, which is the one state in
     *   which Android promises another dialog.
     * @param asked whether this screen has already tried, THIS visit. Not
     *   persisted on purpose: it exists to tell "the dialog has not been tried
     *   yet" from "it was tried and nothing happened", and both halves of that
     *   question live inside one tap.
     */
    fun next(granted: Boolean, rationale: Boolean, asked: Boolean): Next = when {
        granted -> Next.NOTHING
        // Untried: worth a dialog whatever the rationale says, because
        // "never asked" reports exactly the same false as "blocked".
        !asked -> Next.ASK
        // Declined once. Android will show the dialog again, so asking is
        // still the honest thing to offer.
        rationale -> Next.ASK
        // Tried, no dialog, no rationale: it is blocked and only settings
        // can move it.
        else -> Next.OPEN_SETTINGS
    }

    /**
     * What the row says. **Names what is lost rather than what is missing** —
     * "notifications are off" is a fact about the phone, and the pet going
     * unseen is the thing somebody would actually mind.
     */
    const val TITLE = "Your pet cannot reach you"

    const val BODY = "Notifications are off, so the quiet notification that shows " +
        "how your pet is doing — its condition, battery and whether it is " +
        "connected — never appears, and neither do the things it says while you " +
        "are in another app. It has been running and posting the whole time."

    /** The label, which has to say where the tap goes. */
    fun action(next: Next): String? = when (next) {
        Next.NOTHING -> null
        Next.ASK -> "Turn notifications on"
        // Says where it lands, because a button that leaves the app without
        // warning reads as a bug the first time.
        Next.OPEN_SETTINGS -> "Open notification settings"
    }

    // ---- the OTHER half of first run step 5 --------------------------------

    /*
     * TWO GRANTS, AND ONLY ONE OF THEM HAS A DIALOG.
     *
     * `POST_NOTIFICATIONS` above is a runtime permission and can be asked for.
     * Reading what has arrived is a **notification listener binding**, granted
     * only in a system settings screen — there is no API to request it. First
     * run asked for both in one step, and `FirstRunScreen.hasNotifications`
     * already said in a comment that the one with no dialog "is the half that
     * gets skipped by accident".
     *
     * It was. Found on a device 2026-08-27: the listener was not in
     * `enabled_notification_listeners` and had not been for as long as anyone
     * noticed, and the recovery row shipped earlier the same day covered only
     * the permission — so half the door was still one-way.
     *
     * **The switch made it worse than invisible.** *Speak new notifications*
     * read from a preference alone and knew nothing about the binding, so it
     * sat ON above a feature that could not physically work. That is the Ears
     * card's bug in another slot: a control reporting a working state over
     * something that is not working.
     */

    const val LISTENER_TITLE = "Your pet cannot see what arrives"

    const val LISTENER_BODY = "Notification access is off, so the pet is never told " +
        "that anything came in and cannot mention it. This one has no dialog — " +
        "Android grants it only from its own settings screen, which is why it is " +
        "the easiest thing here to lose without noticing."

    /**
     * The label, or null when the binding is held and there is nothing to draw.
     *
     * Only ever one destination: unlike the permission above there is nothing
     * to ask, so there is no escalation to get wrong.
     */
    fun listenerAction(bound: Boolean): String? =
        if (bound) null else "Open notification access"

    /**
     * Whether *Speak new notifications* is worth drawing at all.
     *
     * **Absent rather than drawn refusing**, the same rule as delete on a loaded
     * model row. A switch the user can flip that changes nothing is a worse
     * answer than no switch and a sentence saying why — and the sentence is
     * [LISTENER_BODY], sitting directly above it.
     */
    fun announceSwitchIsUseful(bound: Boolean): Boolean = bound
}
