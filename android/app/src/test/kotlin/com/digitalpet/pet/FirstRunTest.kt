package com.digitalpet.pet

import com.digitalpet.llm.LlmManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First run — DESIGN.md §5.2.
 *
 * The whole flow is a pure function of what is true, so all of it is testable
 * without a device. That was the reason for deriving the step rather than
 * storing one, and this file is the payoff.
 */
class FirstRunTest {

    private val nothing = FirstRunState(
        welcomeAcknowledged = false,
        bluetoothGranted = false,
        petPaired = false,
        readiness = PetReadiness.Missing(PetFaculty.entries),
        usageAccess = false,
        notificationsGranted = false,
    )

    private val everything = FirstRunState(
        welcomeAcknowledged = true,
        bluetoothGranted = true,
        petPaired = true,
        readiness = PetReadiness.Ready,
        usageAccess = true,
        notificationsGranted = true,
    )

    // ---- the order, which is the design ------------------------------------

    @Test
    fun `a fresh install opens on the welcome`() {
        assertEquals(FirstRunStep.WELCOME, FirstRun.currentStep(nothing))
    }

    @Test
    fun `the steps arrive in the order things break`() {
        // Walked forward one satisfied condition at a time, which is the only
        // way to assert the ORDER rather than the membership. §5.2's table is
        // ordered by what breaks first without it, and that ordering is the
        // design decision — a set-equality check would pass on any permutation.
        var state = nothing
        val seen = mutableListOf<FirstRunStep>()
        repeat(FirstRun.total) {
            val step = FirstRun.currentStep(state) ?: return@repeat
            seen += step
            state = when (step) {
                FirstRunStep.WELCOME -> state.copy(welcomeAcknowledged = true)
                FirstRunStep.PET -> state.copy(bluetoothGranted = true, petPaired = true)
                FirstRunStep.MODELS -> state.copy(readiness = PetReadiness.Ready)
                FirstRunStep.SCREEN_TIME -> state.copy(usageAccess = true)
                FirstRunStep.NOTIFICATIONS -> state.copy(notificationsGranted = true)
            }
        }
        assertEquals(
            listOf(
                FirstRunStep.WELCOME,
                FirstRunStep.PET,
                FirstRunStep.MODELS,
                FirstRunStep.SCREEN_TIME,
                FirstRunStep.NOTIFICATIONS,
            ),
            seen,
        )
    }

    @Test
    fun `nothing left to do ends the flow`() {
        assertNull(FirstRun.currentStep(everything))
    }

    @Test
    fun `a step already satisfied is not offered`() {
        // The point of deriving rather than storing: someone who paired their
        // pet before opening the app should not be walked through pairing.
        val paired = nothing.copy(
            welcomeAcknowledged = true, bluetoothGranted = true, petPaired = true,
        )
        assertEquals(FirstRunStep.MODELS, FirstRun.currentStep(paired))
    }

    // ---- the two hand-offs, which is where derived state earns itself -------

    @Test
    fun `coming back with a pet paired is progress, with nothing told`() {
        // PET and MODELS cannot be completed inside the flow — they hand off to
        // the settings pages. Because the step is derived, returning with the
        // work done simply advances; there is no "mark step complete" call that
        // could be forgotten or fire twice.
        val onPetStep = nothing.copy(welcomeAcknowledged = true)
        assertEquals(FirstRunStep.PET, FirstRun.currentStep(onPetStep))

        val back = onPetStep.copy(bluetoothGranted = true, petPaired = true)
        assertEquals(FirstRunStep.MODELS, FirstRun.currentStep(back))
    }

    @Test
    fun `unpairing sends you back to the step you no longer pass`() {
        // The failure a stored index would have: state moves underneath a wizard
        // and the wizard does not notice.
        val undone = everything.copy(petPaired = false)
        assertEquals(FirstRunStep.PET, FirstRun.currentStep(undone))
    }

    // ---- what counts as done ------------------------------------------------

    @Test
    fun `PET needs both the permission and a pairing`() {
        val state = nothing.copy(welcomeAcknowledged = true)
        assertTrue(!FirstRun.isDone(FirstRunStep.PET, state.copy(bluetoothGranted = true)))
        assertTrue(!FirstRun.isDone(FirstRunStep.PET, state.copy(petPaired = true)))
        assertTrue(
            FirstRun.isDone(
                FirstRunStep.PET,
                state.copy(bluetoothGranted = true, petPaired = true),
            )
        )
    }

    @Test
    fun `PET is satisfied by PAIRED, not by connected`() {
        // A pet in the next room is a finished setup step. Requiring a live link
        // would drag someone back through first run for walking away from it.
        // (There is no `connected` field at all, which is the strongest form of
        // this: the flow cannot accidentally depend on it.)
        val outOfRange = everything.copy(petPaired = true)
        assertTrue(FirstRun.isDone(FirstRunStep.PET, outOfRange))
    }

    @Test
    fun `MODELS is satisfied by anything that is not Missing, including a failure`() {
        /*
         * A model that is present and will not load is a DIFFERENT problem with
         * a different fix, and the main surface reports it with the loader's own
         * words. First run's job is only to get the three files onto the device.
         *
         * Sending someone back to a file manager for a file they already have is
         * exactly the fault PetReadiness fixed at its source on 2026-08-05, when
         * a broken Whisper model was indistinguishable from an absent one.
         */
        val base = everything.copy(usageAccess = false, notificationsGranted = false)
        listOf(
            PetReadiness.Ready,
            PetReadiness.Loading(PetFaculty.BRAIN),
            PetReadiness.Failed(PetFaculty.BRAIN, "could not mmap"),
        ).forEach {
            assertTrue(
                "$it should satisfy MODELS",
                FirstRun.isDone(FirstRunStep.MODELS, base.copy(readiness = it)),
            )
        }
        assertTrue(
            !FirstRun.isDone(
                FirstRunStep.MODELS,
                base.copy(readiness = PetReadiness.Missing(listOf(PetFaculty.VOICE))),
            )
        )
    }

    // ---- skipping ------------------------------------------------------------

    @Test
    fun `a skipped optional step is stepped over`() {
        val state = everything.copy(
            usageAccess = false,
            notificationsGranted = false,
            skipped = setOf(FirstRunStep.SCREEN_TIME),
        )
        assertEquals(FirstRunStep.NOTIFICATIONS, FirstRun.currentStep(state))
    }

    @Test
    fun `skipping everything optional ends the flow`() {
        val state = everything.copy(
            usageAccess = false,
            notificationsGranted = false,
            skipped = setOf(FirstRunStep.SCREEN_TIME, FirstRunStep.NOTIFICATIONS),
        )
        assertNull(FirstRun.currentStep(state))
    }

    @Test
    fun `exactly two steps are optional, and they are the two that degrade`() {
        // §5.2's *if declined* column, asserted rather than remembered. Making
        // PET or MODELS optional would let someone finish setup with a product
        // that cannot work, and the flow would have said nothing about it.
        assertEquals(
            listOf(FirstRunStep.SCREEN_TIME, FirstRunStep.NOTIFICATIONS),
            FirstRunStep.entries.filter { it.optional },
        )
    }

    // ---- the copy, which is also the design ----------------------------------

    @Test
    fun `MODELS names only what is actually missing`() {
        val oneMissing = nothing.copy(readiness = PetReadiness.Missing(listOf(PetFaculty.VOICE)))
        val body = FirstRun.body(FirstRunStep.MODELS, oneMissing)
        assertTrue("should name the voice file: $body", body.contains(PetFaculty.VOICE.what))
        assertTrue("should not ask for a brain it has: $body", !body.contains(PetFaculty.BRAIN.what))
    }

    @Test
    fun `MODELS lists all three when all three are missing`() {
        val body = FirstRun.body(FirstRunStep.MODELS, nothing)
        PetFaculty.entries.forEach {
            assertTrue("should name ${it.what}: $body", body.contains(it.what))
        }
    }

    @Test
    fun `every step has a title, a body and an action`() {
        FirstRunStep.entries.forEach { step ->
            assertTrue("${step.name} has no title", FirstRun.title(step).isNotBlank())
            assertTrue("${step.name} has no body", FirstRun.body(step, nothing).isNotBlank())
            assertTrue("${step.name} has no action", FirstRun.action(step).isNotBlank())
        }
    }

    @Test
    fun `the welcome has no way to decline and every other step does`() {
        // A skip on a welcome screen invites someone to miss the one thing the
        // app cannot recover from them not knowing — that there is a physical
        // pet. Every step after it is a request, and a request needs a no.
        assertNull(FirstRun.secondary(FirstRunStep.WELCOME))
        FirstRunStep.entries.drop(1).forEach {
            assertNotNull("${it.name} offers no way out", FirstRun.secondary(it))
        }
    }

    @Test
    fun `the way out says something different when it ends the run`() {
        // Blocking and optional steps must not share a word: "not now" on a step
        // that ends setup would be a lie about what the tap does.
        val blocking = FirstRunStep.entries.filter { !it.optional && it != FirstRunStep.WELCOME }
            .map { FirstRun.secondary(it) }.toSet()
        val optional = FirstRunStep.entries.filter { it.optional }
            .map { FirstRun.secondary(it) }.toSet()
        assertTrue("blocking and optional steps share wording", (blocking intersect optional).isEmpty())
    }

    @Test
    fun `only the optional steps state what declining costs`() {
        // On a blocking step the body has already said the product does not
        // work; repeating it under the button is nagging rather than informing.
        FirstRunStep.entries.forEach { step ->
            if (step.optional) assertNotNull("${step.name} should say what is lost", FirstRun.cost(step))
            else assertNull("${step.name} should not repeat itself", FirstRun.cost(step))
        }
    }

    @Test
    fun `the progress reading counts the step you are looking at`() {
        // "1 of 5" on the first screen, not "0 of 5" — a wizard that opens on
        // zero has told you only that it has five parts.
        assertEquals(1, FirstRun.position(FirstRunStep.WELCOME))
        assertEquals(5, FirstRun.position(FirstRunStep.NOTIFICATIONS))
        assertEquals(5, FirstRun.total)
    }

    // ---- the readiness fold this depends on ---------------------------------

    @Test
    fun `a fresh device really does report Missing, so MODELS really does show`() {
        // Guards the assumption the whole MODELS step rests on. If PetReadiness
        // ever stopped folding "no files at all" to Missing, first run would
        // skip step 3 in silence — which is the exact failure §5.2 calls the
        // silent cliff.
        val fresh = PetReadiness.of(
            llm = LlmManager.ModelState.Unloaded,
            ttsReady = false,
            sttModelName = null,
        )
        assertTrue("fresh install is not Missing: $fresh", fresh is PetReadiness.Missing)
        assertTrue(!FirstRun.isDone(FirstRunStep.MODELS, nothing.copy(readiness = fresh)))
    }
}
