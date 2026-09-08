package com.digitalpet.conversation

import com.digitalpet.ble.PetProtocol

/**
 * What the pet says about ITSELF — fed, played with, or asking for attention.
 *
 * ### Derived from Condition, not from a new event
 *
 * There is no "fed" message on the wire and there does not need to be. Satiety
 * only ever rises when the pet is fed and happiness only when it is played
 * with, so a rise IS the event; `calling` flipping true is the pet asking. The
 * phone watches the Condition it already receives.
 *
 * That is the same reasoning as [com.digitalpet.pet.PetFace]: a shared rule
 * beats a wire field, because a field can go stale and a rule cannot. It also
 * means this works on any firmware that reports Condition at all.
 *
 * ### Everything here is pure
 *
 * [reactTo] takes two readings and returns what to say. No clock, no BLE, no
 * Android. The variety matters as much as the correctness — a pet with one
 * "thank you" is a doorbell — so the line is chosen by a counter the caller
 * owns rather than by a random this cannot test.
 */
object PetVoiceLines {

    enum class Occasion {
        FED, PLAYED, CALLING,

        /**
         * Care offered to a pet that does not need it — "thanks, but I'm full".
         *
         * **The only occasion that cannot come from a Condition transition.**
         * Every other one is derived: satiety only rises when the pet is fed, so
         * a rise IS the event. A REFUSED feed changes nothing at all, so there
         * is nothing for the phone to notice and the pet's "no thank you" would
         * be silent. These arrive as PET_EVT_CARE_NO instead.
         */
        FULL_FED, FULL_PLAYED,
    }

    /*
     * THE WORDS MOVED OUT. They live in design-system/personas/ now, because
     * the pet had two personalities that did not know about each other: a
     * system prompt for the model and these strings for everything the model is
     * too slow to say. Rewriting one left the other in the original register.
     *
     * What stays here is the DERIVATION — which occasion a pair of Condition
     * readings represents. That is a rule about the pet's life rather than
     * about its voice, and no persona gets to change what "fed" means.
     */

    /**
     * What changed between two readings, or null if nothing worth saying did.
     *
     * [before] is null on the first reading after connecting, and that case
     * returns null on purpose: everything looks like a change against nothing,
     * and a pet that greeted every reconnection with "that's better" would be
     * lying about having just been fed.
     */
    fun reactTo(
        before: PetProtocol.Condition?,
        after: PetProtocol.Condition,
    ): Occasion? {
        // A dead pet says nothing at all. It is the one state that cannot be
        // undone, and cheerful noises would undo it.
        if (after.dead) return null

        /*
         * THE FIRST READING CAN STILL REPORT A CALL, and only a call.
         *
         * `before` is null when we have just connected. Treating that as "no
         * news" is right for the scores — nothing was just fed, and saying so
         * would be a lie — but wrong for calling, which is a STATE rather than
         * an event: a pet that has been asking for an hour is still asking, and
         * reconnecting to it in silence is the case where speaking matters
         * most.
         *
         * Found by checking why calling seemed never to fire. It was not the
         * cause — a call needs a score at zero and neither was — but this would
         * have swallowed the first call after every reconnect, which is a
         * harder fault to notice than one that never fires at all.
         */
        if (before == null) return if (after.calling) Occasion.CALLING else null

        // Feeding and playing are the user's own actions and take precedence
        // over the pet's asking — if both changed in one reading, the thing
        // that just happened is the more interesting one.
        if (after.satiety > before.satiety) return Occasion.FED
        if (after.happiness > before.happiness) return Occasion.PLAYED
        // Only the EDGE. `calling` stays true until it is answered, and a pet
        // that repeated itself on every Condition notification would be
        // unbearable within a minute.
        if (after.calling && !before.calling) return Occasion.CALLING
        return null
    }

    /**
     * The line for [occasion]. [nth] rotates through the set, so the pet does
     * not say the same thing twice running — a counter rather than a random,
     * because "it varies" is a property worth being able to assert.
     */
    fun line(persona: PetPersona, occasion: Occasion, nth: Int): String {
        val lines = persona.lines(occasion)
        return lines[Math.floorMod(nth, lines.size)]
    }
}
