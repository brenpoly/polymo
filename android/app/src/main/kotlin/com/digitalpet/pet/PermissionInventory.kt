package com.digitalpet.pet

/**
 * Every grant the pet needs, in one list, with what breaks without each.
 *
 * **Why this page exists.** First run asks for these one at a time, at the step
 * that needs them — DESIGN.md §5.2, and that is right. But first run finishes
 * once and for all, and until now it was the ONLY place any of them was ever
 * requested. Three separate faults in one day traced to the same shape: a step
 * skipped, a grant lost, no way back, and nothing on any screen admitting it.
 * Building a recovery row per feature by hand had already been done twice and
 * missed a third. This is the general answer.
 *
 * **It reports; it does not nag.** §5.2 forbids an opening barrage of dialogs,
 * and a page somebody chooses to visit is not that.
 *
 * **THE ONE THING ANDROID WILL NOT DO IS TAKE A PERMISSION BACK.**
 * `revokeSelfPermissionOnKill` exists for runtime permissions on API 33+ and
 * applies by killing the process — here that drops the BLE link, unloads a
 * multi-gigabyte model and costs the ~18 s reload measured on device. The
 * listener binding and the usage-access appop have no revoke API at all. So
 * these are **state rows with an action, not switches**: a switch that cannot
 * go back, or that snaps back after being tapped, is the same lie as a *Loaded*
 * badge over a model that failed.
 */
object PermissionInventory {

    /**
     * How a grant is obtained, which decides what the button can say.
     *
     * The distinction is not cosmetic. [DIALOG] can be asked for in-app;
     * [SETTINGS_TRIP] cannot be asked for at all and is the kind that goes
     * missing quietly, because nothing ever interrupts to mention it.
     */
    enum class How { DIALOG, SETTINGS_TRIP }

    /**
     * **Ordered by how badly the pet is broken without it**, the same rule as
     * [PetFaculty] and the settings index: without Bluetooth there is no pet at
     * all; without usage access the illness mechanic — the whole product
     * direction — silently does nothing; the microphone is last because the
     * pet's own mic is the primary path and this is only the phone fallback.
     *
     * The order is fixed rather than missing-first. A list that reorders itself
     * as you grant things moves the row you are about to tap.
     */
    /**
     * **Named for the capability, not for the feature.** The first version of
     * this page said "See what arrives" and "Connect to your pet" — the
     * codebase's house voice, which names a thing as its owner would say it,
     * the way [PetFaculty] says "hear you" rather than "Whisper". That voice is
     * right for describing what is BROKEN and wrong for asking someone to grant
     * something: "See what arrives" quietly omits that the app can read every
     * notification on the phone, including its contents. A consent surface has
     * to say the capability plainly enough to be refused.
     *
     * **[allows] is the ceiling, [use] is the floor, and the gap between them is
     * the point.** Android grants usage access for every app on the phone; this
     * one reads the handful you chose. Saying only the narrow half would be
     * flattering and untrue, and saying only the broad half would be alarming
     * and equally untrue. Both, in that order — what you are handing over first,
     * what is done with it second.
     *
     * **Ordered by how badly the pet is broken without it**, the same rule as
     * [PetFaculty] and the settings index. Fixed rather than missing-first: a
     * list that reorders as grants land moves the row you are reaching for.
     */
    enum class Grant(
        /** The capability, in the terms the system would use for it. */
        val title: String,
        /** What granting it ALLOWS — true whether or not this app uses all of it. */
        val allows: String,
        /** What this app actually does with it. Narrower than [allows], always. */
        val use: String,
        /** What stops working without it. Shown only when it is missing. */
        val consequence: String,
        val how: How,
    ) {
        BLUETOOTH_CONNECT(
            title = "Connect to Bluetooth devices",
            allows = "Lets PolyMO open a connection to Bluetooth devices this phone " +
                "has already been paired with, and exchange data with them.",
            use = "It connects to your pet and to nothing else.",
            consequence = "Without it the pet cannot be reached at all, and nothing " +
                "else on this page matters.",
            how = How.DIALOG,
        ),
        BLUETOOTH_SCAN(
            title = "Scan for nearby Bluetooth devices",
            allows = "Lets PolyMO see Bluetooth devices around you while it is " +
                "looking for a pet.",
            // True and worth saying: the manifest declares neverForLocation, which
            // is why pairing a pet never asks for a location permission.
            use = "Declared \"never for location\", so Android will not allow it to " +
                "be used to work out where you are. Needed only to pair a new pet.",
            consequence = "Without it you cannot pair a new pet. One that is already " +
                "paired still reconnects.",
            how = How.DIALOG,
        ),
        USAGE_ACCESS(
            title = "Read your app usage history",
            allows = "Lets PolyMO see which apps you have opened and how long you " +
                "spent in each. Android grants this for every app on the phone, not " +
                "only the ones you pick.",
            use = "PolyMO keeps only the apps you chose to track and ignores the rest.",
            consequence = "Without it your pet cannot fall ill, and that is the " +
                "mechanic the whole thing is built on.",
            how = How.SETTINGS_TRIP,
        ),
        POST_NOTIFICATIONS(
            title = "Show notifications",
            allows = "Lets PolyMO put notifications in your notification shade.",
            use = "It posts one: the quiet notification carrying your pet's " +
                "condition, its battery and the Stop action.",
            consequence = "Without it that notification never appears, however the " +
                "pet is doing.",
            how = How.DIALOG,
        ),
        NOTIFICATION_ACCESS(
            title = "Read phone notifications",
            allows = "Lets PolyMO read every notification this phone receives, " +
                "including who sent it and what it says. This is the broadest thing " +
                "on this page.",
            // Verifiable rather than reassuring: PetAnnouncement.Item carries an app
            // label and a dedupe key, and nothing else ever reaches the pet.
            use = "PolyMO keeps only the name of the app it came from and a count. " +
                "No message text is stored, and your pet is told no more than " +
                "\"one new notification from Mail\".",
            consequence = "Without it the pet is never told anything arrived, so it " +
                "cannot mention it.",
            how = How.SETTINGS_TRIP,
        ),
        RECORD_AUDIO(
            title = "Record audio with this phone's microphone",
            allows = "Lets PolyMO record from this phone's microphone.",
            use = "Only while you are recording a message from inside the app. The " +
                "pet has its own microphone, and that is the normal way to talk to it.",
            consequence = "Without it only the phone-microphone fallback stops " +
                "working; you can still talk to the pet through the pet.",
            how = How.DIALOG,
        ),
    }

    /** One line of the page. */
    data class Row(val grant: Grant, val held: Boolean, val action: String?)

    /**
     * The page, in order, given what is currently held.
     *
     * **A held grant has no action**, the same rule as delete on a loaded model
     * row: there is nothing this app can do to a permission it already has, and
     * a button that opens system settings so you can *remove* it is not an
     * offer worth making on every row. Turning one off is a deliberate act and
     * belongs where the system keeps it.
     */
    fun rows(held: Map<Grant, Boolean>): List<Row> =
        Grant.entries.map { g ->
            val has = held[g] == true
            Row(g, has, if (has) null else actionFor(g))
        }

    /**
     * The button, worded so it says where the tap goes.
     *
     * A settings trip that looks like a dialog reads as a bug the first time it
     * throws you out of the app.
     */
    fun actionFor(grant: Grant): String = when (grant.how) {
        How.DIALOG -> "Grant"
        How.SETTINGS_TRIP -> "Open settings"
    }

    /**
     * The floor under every row on the page, and it is checkable rather than
     * reassuring.
     *
     * **The app declares no `INTERNET` permission at all** — see the manifest,
     * and `PermissionCopyTest` fails if that ever stops being true. So whatever
     * these grants let PolyMO read, none of it can be sent anywhere: Whisper,
     * the language model and Piper all run on the phone, and the only thing that
     * leaves it is a sentence going to your pet over Bluetooth.
     *
     * Worth stating once at the top rather than hedging it into six rows.
     */
    const val NOTHING_LEAVES = "None of this can leave your phone. PolyMO has no " +
        "internet permission at all — it does its listening, thinking and speaking " +
        "here, and the only thing it sends anywhere is a sentence to your pet."

    /**
     * The page's own subtitle, and the settings index's — **one definition, so
     * the row you tap and the page you land on cannot describe different
     * things.**
     *
     * Capability framing like the rows: it used to read "What the pet is allowed
     * to do", which names the pet rather than the phone and quietly makes this
     * sound like a page about the toy.
     */
    const val SUBTITLE = "What you have allowed PolyMO to do on this phone, and what you have not"

    /**
     * Whether a row starts open.
     *
     * **Open when the grant is MISSING, closed when it is held**, which is
     * `SlotCard`'s rule — a card at rest says what is loaded and nothing else,
     * because that is the only thing that matters when nothing is wrong.
     *
     * The tension is real and this is the resolution: the descriptions are what
     * make this a consent surface rather than a checklist, so hiding them by
     * default would undo the point. But they matter **at the moment of
     * deciding**, and that moment is when the grant is missing. A held row is a
     * decision already made, available to re-read on a tap. The full text is
     * always one tap away and never more than one.
     */
    fun startsExpanded(held: Boolean): Boolean = !held

    /** How many are missing, for the summary line and the settings-index subtitle. */
    fun missingCount(held: Map<Grant, Boolean>): Int = Grant.entries.count { held[it] != true }

    /**
     * The line at the top of the page.
     *
     * **Says the number, not "some permissions are missing".** A count is
     * actionable and a vague plural is not, and this page exists because things
     * were going missing unnoticed.
     */
    fun summary(held: Map<Grant, Boolean>): String = when (val n = missingCount(held)) {
        0 -> "Everything the pet needs has been granted."
        1 -> "One thing is missing. Your pet is not doing everything it could."
        else -> "$n things are missing. Your pet is not doing everything it could."
    }
}
