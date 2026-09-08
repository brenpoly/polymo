package com.digitalpet.pet

import com.digitalpet.ble.PetProtocol

/**
 * How the pet's condition reads to a person.
 *
 * Pure and Android-free on purpose, following the `PetExpression.parse`
 * precedent: this is the mapping most likely to drift from what the pet
 * actually means, and a mapping pinned by tests is worth more than one pinned
 * by a comment.
 *
 * **The precedence here mirrors the firmware's own**, from DESIGN.md §1: dead
 * outranks sick, sick outranks the scores. If the pet's face and this text ever
 * disagree about what matters most, one of them is lying to the user — and the
 * face is the one they will believe, because it is the pet.
 */
object PetStatusText {

    /**
     * Whether the pet is actually there, in the user's words.
     *
     * The asymmetry with the pet's own indicator is the interesting part. The
     * phone can *ask* — it holds the GATT connection and knows every state of
     * it, including "trying" — whereas the pet can only notice that nobody is
     * talking to it. So this has four states and the pet's dot has two, and
     * that is correct rather than an inconsistency to iron out.
     *
     * [paired] separates the two silences that look identical on screen and
     * mean opposite things: never set up, versus set up and out of range.
     */
    fun connectionLabel(
        state: com.digitalpet.ble.PetBleRepository.State,
        paired: Boolean,
        bluetoothOn: Boolean = true,
        switchedOff: Boolean = false,
    ): String = when {
        /*
         * "SWITCHED OFF" OUTRANKS EVERYTHING, INCLUDING BLUETOOTH — DESIGN.md
         * §5.3 and §5.4.
         *
         * It is the only state here the user created deliberately, so it is the
         * true answer to "why is nothing showing". Reporting "Not connected"
         * instead invites them to go and find a pet they switched off on purpose,
         * and reporting "Bluetooth is off" would blame the radio for a decision
         * they made — accurate about the radio, wrong about the pet.
         *
         * It also has to be said at all, because the stop is now PERSISTED: an
         * off switch that survives reopening the app is only an improvement if
         * the app admits the pet is off. Otherwise it is a pet that never comes
         * back and never says why.
         */
        switchedOff -> "Your pet is off"
        /*
         * BLUETOOTH OFF OUTRANKS EVERYTHING, including READY — DESIGN.md §5.3.
         *
         * It has to, because it is the one state here whose cause is not the pet
         * and whose cure is not in this app. "Not connected" invites you to go
         * and look for the pet; "Bluetooth is off" tells you the search cannot
         * happen at all. Sending someone to hunt a pet that is sitting right in
         * front of them is the specific harm.
         *
         * Ranked above READY on purpose: the adapter can be switched off while a
         * link is up, and the stale READY would otherwise outlive the radio.
         */
        !bluetoothOn -> "Bluetooth is off"
        state == com.digitalpet.ble.PetBleRepository.State.READY -> "Connected"
        state == com.digitalpet.ble.PetBleRepository.State.CONNECTING -> "Connecting…"
        state == com.digitalpet.ble.PetBleRepository.State.SCANNING -> "Looking for your pet…"
        // IDLE splits the two silences that look identical and mean opposites.
        paired -> "Not connected"
        else -> "No pet paired yet"
    }

    /**
     * True when the app should stop claiming to know how the pet is, *including*
     * because the radio is off.
     *
     * Separate from [readingsAreStale] taking a state alone so existing callers
     * keep working; both exist because blanking the readings is the rule that
     * DESIGN.md §5.0 rule 2 makes non-negotiable — never assert what we have not
     * been told.
     */
    fun readingsAreStale(
        state: com.digitalpet.ble.PetBleRepository.State,
        bluetoothOn: Boolean,
        switchedOff: Boolean = false,
    ): Boolean = switchedOff || !bluetoothOn || readingsAreStale(state)

    /**
     * True when the app should stop claiming to know how the pet is.
     *
     * A card showing satiety and happiness from a link that dropped ten minutes
     * ago is stating yesterday's news as fact. The pet goes on living while the
     * phone is away — that is DESIGN.md §1 decision 1 — so those numbers are not
     * merely stale, they are wrong in a direction nobody can predict.
     */
    fun readingsAreStale(state: com.digitalpet.ble.PetBleRepository.State): Boolean =
        state != com.digitalpet.ble.PetBleRepository.State.READY

    /**
     * What the notification's action will actually do if pressed.
     *
     * It said "Stop pet" in every state, including the ones with no pet in them.
     * The service outlives the connection — it retries, and it starts itself
     * after a reboot — so the button is frequently offered while nothing is
     * connected, where "stop pet" describes an act that is not available and
     * hides the one that is: calling off the search.
     *
     * Both do the same thing to the same service. The label is the only part
     * that was lying.
     */
    fun stopActionLabel(state: com.digitalpet.ble.PetBleRepository.State): String =
        if (state == com.digitalpet.ble.PetBleRepository.State.READY) "Stop pet"
        else "Stop looking"

    /** Filled and empty pips, e.g. `●●○○` for 2 of 4. */
    fun pips(score: Int, max: Int = PetProtocol.Condition.MAX_SCORE): String {
        val filled = score.coerceIn(0, max)
        return "●".repeat(filled) + "○".repeat(max - filled)
    }

    /**
     * Null is **"not known yet"**, never a default.
     *
     * The same rule the prompt clause and the Condition parser follow, and it
     * matters more here than anywhere: this text is on a screen. A card that
     * says "starving" because nothing has connected yet would have people
     * feeding a pet that is perfectly happy — or worse, distrusting the one
     * reading in the app that is supposed to be authoritative.
     */
    fun stageLabel(stage: PetProtocol.Stage?): String = when (stage) {
        PetProtocol.Stage.EGG -> "egg"
        PetProtocol.Stage.CHILD -> "child"
        PetProtocol.Stage.TEEN -> "teen"
        PetProtocol.Stage.ADULT -> "adult"
        null -> "—"
    }

    /**
     * **Whether the life stage is NAMED on a surface. It is not, for now.**
     *
     * Deliberate scope, 2026-08-27, and it is a change to what is SAID rather
     * than to what is true. The stage is still modelled on the pet, still on the
     * wire, still sets how fast the scores decay, still tells the LLM whether it
     * is speaking as something young, and **still visibly changes how big the pet
     * is drawn** — `PetFaceSets.stageScale` is untouched, so an egg is still 55%
     * of an adult on screen. What it no longer has is a word.
     *
     * The reason is that stages are going to be built out, and a bare noun with
     * nothing behind it teaches the wrong thing: "egg" beside a battery reading
     * looks like a spec, invites the question "what happens when it hatches?",
     * and the answer for the first release is "not much yet". Growing silently
     * promises nothing it cannot keep.
     *
     * **ONE SWITCH.** Flip this and both surfaces come back together; the words
     * themselves are kept in [stageLabel] rather than deleted, so turning it on
     * is one line and cannot half-land. Not `const`, so flipping it does not
     * make the call sites fold away into compiler warnings.
     */
    val stageIsNamed: Boolean = false

    /**
     * The stage as a surface should print it, or **null while it is unnamed** —
     * which every caller already handles, because a stage has always been
     * absent on a pet that has not reported one yet.
     */
    fun stageLabelOnSurface(stage: PetProtocol.Stage?): String? =
        if (stageIsNamed && stage != null) stageLabel(stage) else null

    /**
     * `42%` when the gauge is believed, `3.72V` when only the voltage is, and
     * `—` when neither.
     *
     * Both are shown rather than just the percentage because this gauge has
     * already been wrong in a way that looked plausible: the AXP2101's coulomb
     * counter read 0% through a hundred millivolts of charging until a full
     * discharge gave it a reference. A percentage with no voltage beside it
     * cannot be sanity-checked by the person reading it.
     */
    fun batteryLabel(percent: Int?, millivolts: Int?): String {
        val volts = millivolts?.let { "%.2fV".format(it / 1000.0) }
        return when {
            percent != null && volts != null -> "$percent% · $volts"
            percent != null -> "$percent%"
            volts != null -> volts
            else -> "—"
        }
    }

    /**
     * One line saying what the pet needs, in the pet's order of urgency.
     *
     * Deliberately phrased as what the *owner* should do rather than as a
     * status, because that is the difference between a gauge and a care app.
     */
    fun headline(
        condition: PetProtocol.Condition?,
        discreet: Boolean = false,
        switchedOff: Boolean = false,
    ): String {
        /*
         * SWITCHED OFF OUTRANKS EVERYTHING HERE TOO, for the reason
         * [connectionLabel] gives — it is the only state the user created
         * deliberately, so it is the true answer to "why is nothing showing".
         *
         * Without it the card said **"Waiting to hear from your pet…"** while
         * the chip above it said "Your pet is off": the screen simultaneously
         * blaming a silence on the pet and admitting the user had caused it, and
         * waiting for a message that by construction will never come.
         *
         * The wording is careful about whose life this is. Stopping the link
         * does not stop the PET — DESIGN.md §1 decision 1 puts the simulation on
         * the device, and its clock keeps running whatever the phone does. What
         * stopped is the watching, and saying so is what makes turning it back
         * on feel like resuming rather than like restarting something.
         */
        if (switchedOff) return "You switched your pet off. It is living on without you."
        if (condition == null) return "Waiting to hear from your pet…"

        // Same ranking the firmware applies to the face: dead, then sick, then
        // the scores. Keeping them in step is the whole point.
        if (condition.dead) return "Your pet has died."
        // [discreet] is for the lock screen. Sickness is the ONE line here that
        // is a statement about the user rather than about the pet — it means
        // "you have been on your phone too long" — and the lock screen is
        // readable by anyone who glances at a desk. The pet still says it is
        // unwell, so nothing is hidden from its owner; only the reason is held
        // back until the phone is unlocked. Death is not masked: that is a fact
        // about the pet, and a discreet version of it would be absurd.
        if (condition.sick) {
            return if (discreet) "Not feeling well." else "Unwell — too much screen time."
        }

        val starving = condition.satiety == 0
        val miserable = condition.happiness == 0
        return when {
            starving && miserable -> "Starving and miserable. It needs you now."
            starving -> "Starving. Feed it — double tap."
            miserable -> "Miserable. Play with it — shake for a second."
            // `calling` is only meaningful while a score is 0, and the two
            // cases above already cover that with something actionable. It is
            // checked after them so a call never replaces the instruction that
            // would actually answer it.
            condition.calling -> "Asking for attention."
            condition.satiety <= 2 || condition.happiness <= 2 -> "Could use some attention."
            else -> "Doing well."
        }
    }

    /**
     * The notification's second line: the link, then what is known about the pet.
     *
     * The link comes FIRST because everything after it is only as true as it is —
     * the same ordering as the card, for the same reason.
     */
    fun notificationSubtitle(
        condition: PetProtocol.Condition?,
        state: com.digitalpet.ble.PetBleRepository.State,
        paired: Boolean
    ): String {
        if (readingsAreStale(state)) return connectionLabel(state, paired)

        val bits = buildList {
            add("connected")
            // Absent rather than blank while the stage is unnamed — the join
            // below would otherwise leave a stray separator. See [stageIsNamed].
            stageLabelOnSurface(condition?.stage)?.let { add(it) }
            condition?.batteryPercent?.let { add("$it%") }
        }
        return bits.joinToString(" · ")
    }

    /**
     * Care mistakes, worded so the number means something.
     *
     * Shown at all because it is the one figure that shortens the pet's life —
     * 36 minutes off the death window each, per DESIGN.md §1 — and a hidden
     * counter that kills your pet is a worse surprise than a visible one.
     */
    // ---- the scan block, and when it should exist at all -------------------

    /**
     * Whether to offer *Scan for pets*.
     *
     * **Only when nothing is paired, because this app remembers exactly one
     * pet.** `PetBleRepository.connect` sets `pairedAddress` outright, so
     * tapping a discovered device while already paired **silently replaces the
     * pet** — no confirmation, no mention that it happened. Offering a scan in
     * that state is offering a swap that looks like a search.
     *
     * The way to a different pet is *Forget*, which sits directly above and says
     * what it does. One more tap, and the destructive step is the one that
     * announces itself.
     */
    fun showScan(paired: Boolean): Boolean = !paired

    /**
     * What to say under the scan button, or **null when there is nothing to
     * report**.
     *
     * **A result requires a search.** The old line was drawn whenever the
     * discovered list was empty, which it is before any scan has ever run — so
     * an untouched page announced "No other pets found yet. Make sure the pet is
     * powered on." directly beneath a card reading *Connected*. It stated the
     * outcome of a search nobody had performed, and contradicted the screen it
     * was on.
     */
    fun scanHint(scanning: Boolean, hasScanned: Boolean, found: Int): String? = when {
        scanning -> "Looking…"
        // Nothing has been asked, so there is nothing to answer.
        !hasScanned -> null
        found == 0 -> "No pets found. Make sure yours is powered on and nearby."
        else -> null
    }

    fun careMistakesLabel(count: Int): String = when (count) {
        0 -> "No care mistakes"
        1 -> "1 care mistake"
        else -> "$count care mistakes"
    }
}
