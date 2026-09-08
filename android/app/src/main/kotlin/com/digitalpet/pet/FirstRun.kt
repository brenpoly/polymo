package com.digitalpet.pet

/**
 * First run — DESIGN.md §5.2, the last unbuilt flow.
 *
 * **The sequence is ordered by what breaks first without it**, not by what is
 * easiest to ask for. Without a pet nothing works at all; without models the pet
 * listens, understands and says nothing; without screen-time access the whole
 * sickness mechanic silently does nothing; notifications are a nicety.
 *
 * ### The step is DERIVED, never stored
 *
 * Nothing here remembers "the user is on step 3". The current step is computed
 * from what is actually true — is a pet paired, are the models loaded, is usage
 * access granted — every time it is asked for. That is §5.0 rule 2 applied to a
 * wizard: a stored index is an assertion about the world that stops being true
 * the moment somebody unpairs a pet or deletes a model, and it would send them
 * forward past a step they no longer pass.
 *
 * It also makes the two hand-offs work for free. Steps 2 and 3 cannot be
 * completed inside this flow — pairing happens on the *Your pet* page and models
 * are imported on *Local AI models* — so the flow sends you there and you come
 * back. Because the step is derived, coming back with a pet paired simply *is*
 * progress; nothing has to be told.
 *
 * ### One thing is remembered, and one thing only
 *
 * Whether the flow has been finished or dismissed — see `FirstRunRepository`.
 * Everything else is read from the world.
 *
 * ### Blocked is not the same as skippable, and the wording carries it
 *
 * §5.2's *if declined* column is the whole of it. Steps 2 and 3 **block**: there
 * is no version of this product that works without them, so declining ends the
 * run rather than stepping over it. Steps 4 and 5 **degrade**: the app still
 * works, one named thing stops, and you are offered the step again.
 *
 * **A run that stops at step 2 has still achieved something**, which is why
 * leaving is never a dead end: the main surface already reports every one of
 * these states in the place that fixes it — the two chips, the care card, the
 * usage-access chip on the screen-time page.
 */
enum class FirstRunStep(
    /** Whether declining leaves the product working. §5.2's *if declined*. */
    val optional: Boolean,
) {
    /**
     * The one expectation the app cannot recover from being wrong about: there
     * is a physical pet, and this is its other half.
     */
    WELCOME(optional = false),

    /** Bluetooth, then pairing. Nothing works without a pet — this is the product. */
    PET(optional = false),

    /**
     * The silent cliff. Without all three the pet listens, understands and says
     * nothing — the documented `SttService not initialised` silence, visible
     * only in a log.
     */
    MODELS(optional = false),

    /** The mechanic, not a permission grab: it is what makes the pet ill. */
    SCREEN_TIME(optional = true),

    /** The foreground notification and the summaries. A nicety. */
    NOTIFICATIONS(optional = true),
}

/**
 * What is true about the world, as far as first run is concerned.
 *
 * A parameter object rather than six arguments, because every one of them is a
 * `Boolean` and a call site that got two of them the wrong way round would
 * compile and be wrong. Named fields make that a typo you can see.
 */
data class FirstRunState(
    /** The welcome step is the only one completed by reading it. */
    val welcomeAcknowledged: Boolean,
    /** `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`, or below API 31 where they are implicit. */
    val bluetoothGranted: Boolean,
    /** A pet has been paired at some point. Not the same as currently connected. */
    val petPaired: Boolean,
    /** Folded from the three model slots. See [PetReadiness]. */
    val readiness: PetReadiness,
    /** The usage-access appop. Not a permission — `checkSelfPermission` cannot answer it. */
    val usageAccess: Boolean,
    /** Both halves: `POST_NOTIFICATIONS` and the notification listener. */
    val notificationsGranted: Boolean,
    /** Optional steps the user has stepped over in this run. Not persisted. */
    val skipped: Set<FirstRunStep> = emptySet(),
)

object FirstRun {

    /**
     * Whether a step has been satisfied by the world.
     *
     * **[FirstRunStep.PET] is paired, not connected**, and that distinction is
     * load-bearing. A pet that is paired and out of range is a finished setup
     * step; treating it as incomplete would drag someone back through first run
     * because they walked into the next room.
     *
     * **[FirstRunStep.MODELS] is satisfied by anything that is not
     * [PetReadiness.Missing]**, including `Failed`. A model that is present and
     * will not load is a different problem with a different fix — the main
     * surface reports it with the loader's own words — and first run's job is
     * only to get the three files onto the device. Sending someone back to a
     * file manager for a file they already have is the fault `PetReadiness`
     * fixed at its source in 2026-08-05.
     */
    fun isDone(step: FirstRunStep, state: FirstRunState): Boolean = when (step) {
        FirstRunStep.WELCOME -> state.welcomeAcknowledged
        FirstRunStep.PET -> state.bluetoothGranted && state.petPaired
        FirstRunStep.MODELS -> state.readiness !is PetReadiness.Missing
        FirstRunStep.SCREEN_TIME -> state.usageAccess
        FirstRunStep.NOTIFICATIONS -> state.notificationsGranted
    }

    /**
     * The step to show, or **null when there is nothing left to do**.
     *
     * Null is what ends the flow. It arrives either because everything is set up
     * or because every remaining step has been stepped over, and the caller
     * treats both the same way: mark first run finished and go to the pet.
     */
    fun currentStep(state: FirstRunState): FirstRunStep? =
        FirstRunStep.entries.firstOrNull { !isDone(it, state) && it !in state.skipped }

    /**
     * How far along, for a progress reading — `2 of 5`.
     *
     * Counts the step being shown, so it reads as *this is the second of five*
     * rather than as *one is behind you*. A wizard that opens on "0 of 5" has
     * told you nothing except that it has five parts.
     */
    fun position(step: FirstRunStep): Int = FirstRunStep.entries.indexOf(step) + 1

    val total: Int = FirstRunStep.entries.size

    // ---- copy -------------------------------------------------------------

    /**
     * The title. **Says what the owner gets, not what the app requires** — the
     * design system's rule that separates a care app from a permissions form.
     */
    fun title(step: FirstRunStep): String = when (step) {
        FirstRunStep.WELCOME -> "Your pet is a real thing."
        FirstRunStep.PET -> "Find your pet."
        FirstRunStep.MODELS -> "Give it a brain, ears and a voice."
        FirstRunStep.SCREEN_TIME -> "Decide what makes it ill."
        FirstRunStep.NOTIFICATIONS -> "Let it get a word in."
    }

    /**
     * The body.
     *
     * [FirstRunStep.MODELS] names what is actually missing rather than asking
     * for all three every time, because the common case on a second run is one
     * file — and "add a model" sends someone to a search engine where
     * "a Piper .onnx voice and its .onnx.json" sends them to the right files.
     * [PetFaculty.what]
     * already carries those words; this is the one screen that needs them in a
     * sentence rather than a list.
     */
    fun body(step: FirstRunStep, state: FirstRunState): String = when (step) {
        FirstRunStep.WELCOME ->
            "It lives on a little screen with its own speaker, and it goes on " +
                "living whether or not this app is open. This app is its other " +
                "half: it does the thinking, and it is where you find out how " +
                "the pet is doing."

        FirstRunStep.PET ->
            "The two halves talk over Bluetooth, and nothing works until they " +
                "have been introduced. You will need the pet switched on and " +
                "nearby."

        FirstRunStep.MODELS -> {
            val missing = (state.readiness as? PetReadiness.Missing)?.which ?: PetFaculty.entries
            val list = when (missing.size) {
                1 -> missing[0].what
                else -> missing.dropLast(1).joinToString(", ") { it.what } +
                    " and " + missing.last().what
            }
            "The pet thinks on your phone, so the models live here. It needs " +
                "$list. Without them it will listen to you, understand nothing, " +
                "and say nothing back."
        }

        FirstRunStep.SCREEN_TIME ->
            "Your pet gets sick when you spend too long in the apps you choose. " +
                "That needs usage access, and it is the whole mechanic rather " +
                "than a permission grab — without it your pet cannot fall ill " +
                "at all."

        FirstRunStep.NOTIFICATIONS ->
            "Two things, both optional. A quiet notification so you can see how " +
                "the pet is without opening this, and permission to read what " +
                "is waiting so it can tell you what came in."
    }

    /** The primary action's label. */
    fun action(step: FirstRunStep): String = when (step) {
        FirstRunStep.WELCOME -> "Set up my pet"
        FirstRunStep.PET -> "Pair a pet"
        FirstRunStep.MODELS -> "Add models"
        FirstRunStep.SCREEN_TIME -> "Choose apps"
        FirstRunStep.NOTIFICATIONS -> "Allow"
    }

    /**
     * The way out, and **the word changes with what declining costs.**
     *
     * On a blocking step it ends the run, so it says so: there is no stepping
     * over a pet that is not paired. On an optional one it steps to the next,
     * and "not now" is honest because the step comes back — the main surface
     * keeps reporting it in the place that fixes it.
     *
     * Null on the first step: there is nothing to decline about being told what
     * the product is, and a *skip* on a welcome screen invites the reader to
     * miss the one thing the app cannot recover from them not knowing.
     */
    fun secondary(step: FirstRunStep): String? = when (step) {
        FirstRunStep.WELCOME -> null
        FirstRunStep.PET, FirstRunStep.MODELS -> "I'll finish setting up later"
        FirstRunStep.SCREEN_TIME, FirstRunStep.NOTIFICATIONS -> "Not now"
    }

    /**
     * What is lost by declining, shown under the secondary action on the steps
     * where something actually is.
     *
     * **Only on the optional steps.** On a blocking one the body has already
     * said the product does not work, and repeating it under the button would be
     * nagging rather than informing.
     */
    fun cost(step: FirstRunStep): String? = when (step) {
        FirstRunStep.SCREEN_TIME -> "Your pet will not be able to fall ill."
        FirstRunStep.NOTIFICATIONS -> "You will only hear from it in the app."
        else -> null
    }
}
