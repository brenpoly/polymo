package com.digitalpet.pet

import com.digitalpet.ble.PetProtocol

/**
 * WHICH face the pet is wearing. What that face LOOKS like is a [PetFaceSet].
 *
 * **The pet's face is the primary state display** — DESIGN.md §6 — and until now
 * only the pet drew it. The app's panel drew one fixed smile and the
 * notification drew two eyes, so the surface people look at all day and the
 * surface in their hand both disagreed with the thing on the shelf.
 *
 * ### The rule lives here; the drawing does not
 *
 * This enum used to carry the geometry as well, hand-copied from `apply_mood()`
 * with a test whose job was to fail when the copy drifted. The numbers are
 * generated into [PetFaceSets] now, from `design-system/faces/`, so the device
 * and the two surfaces on the phone are built from one source and cannot drift.
 *
 * What stays here is [of], a line-for-line mirror of `pet_sim_baseline_mood()`
 * in `pet_sim.c`. That is deliberate and is the exception CLAUDE.md records: the
 * design system owns what a face looks like, and the code remains authoritative
 * about what a face MEANS. A mock may not decide when the pet is starving.
 *
 * ### Why the phone can know this at all
 *
 * **`SAD`, `SICK` and `DEAD` are pet-local and never travel** — `pet.h` says so.
 * That looked like it made this impossible, and it does not: the *rule* is a
 * pure function of `Condition`, which the pet notifies in full. So the phone
 * does not need to be told the face; it can derive the same one from the same
 * inputs, which is a stronger guarantee than a wire field would be. A field
 * could go stale. A shared rule cannot.
 *
 * ### What the phone deliberately does NOT show
 *
 * The pet ranks **dead > sick > a transient expression > the baseline**. The
 * transient ones — `HAPPY`, `SLEEPY`, `SURPRISED` from an emoji in a reply — are
 * written by the phone, decay on the pet, and are not reported back, so the
 * phone cannot know when one is showing. They are therefore left out here rather
 * than guessed at: this renders the **baseline**, which is what the pet shows
 * except during the seconds after it speaks.
 *
 * That is a real difference between the surfaces and it is the honest one. §5.0
 * rule 2 says never assert what we have not been told; a phone animating a wink
 * it merely hopes is happening would be doing exactly that.
 */
enum class PetFace {
    /** Everything is fine. The wide-eyed resting face. */
    HAPPY,

    /** Getting by. The design system's `resting`. */
    NEUTRAL,

    /**
     * Something is at zero, or nearly. **Deliberately readable across a room**
     * — the firmware's own words — because this is the face that has to make
     * somebody go and feed the thing.
     */
    SAD,

    /**
     * Too much screen time. **Not a variation on [SAD]**, and the firmware is
     * emphatic about why: the two mean different things and demand different
     * actions, so they must not be confusable at a glance. Queasy, not
     * miserable.
     */
    SICK,

    /**
     * The one state that cannot be undone. Closed eyes and **no mouth at all**,
     * and it is the least animated thing on any of these screens: the blink and
     * the breathe are what make the pet look alive, so their absence is the
     * whole message.
     */
    DEAD;

    companion object {
        /**
         * **The width of the pet's SCREEN, which is not the width of its panel.**
         *
         * The panel is wired portrait, 368x448, and the firmware rotates it 90°
         * — `PET_ROTATION` in `pet-esp32.c` — so what the pet actually shows is
         * 448 across by [SCREEN_HEIGHT] down. The design has always drawn the
         * pet in landscape; the device was the surface that had not caught up.
         *
         * This is the divisor that maps the face onto any other surface, so it
         * has to be the *screen*. Using 368 here, as this did until the rotation
         * landed, drew the face 22% too large for its panel — the app showed a
         * pet whose eyes reached noticeably further across than the real one's.
         *
         * Face geometry is untouched by this: a set's numbers are device pixels
         * and a rotation does not resize anything.
         */
        const val SCREEN_WIDTH = 448

        /** The other edge of the rotated screen — `PET_SCREEN_H` in the firmware. */
        const val SCREEN_HEIGHT = 368

        /**
         * Which face the pet is showing, from what it has told us.
         *
         * **A line-for-line mirror of `pet_sim_baseline_mood()`**, including the
         * order, because the order is the design: dead outranks sick outranks
         * the scores. Reordering these silently would put a queasy face on a
         * dead pet.
         *
         * The zero floor is not redundant with the sum. Satiety 0 with happiness
         * 4 sums to 4, which would otherwise land in [NEUTRAL] and show a
         * contented face on a starving pet — the firmware calls that out
         * specifically, and a mirror that dropped it would be wrong in exactly
         * the case that matters most.
         *
         * **Null is [NEUTRAL] rather than a guess.** Nothing has been heard, so
         * there is no face to report; the surfaces that draw it dim it instead,
         * which is §5.0 rule 2 done in a picture rather than in a value.
         */
        fun of(condition: PetProtocol.Condition?): PetFace {
            if (condition == null) return NEUTRAL
            if (condition.dead) return DEAD
            if (condition.sick) return SICK
            if (condition.satiety == 0 || condition.happiness == 0) return SAD
            val total = condition.satiety + condition.happiness
            return when {
                total <= 2 -> SAD
                total <= 5 -> NEUTRAL
                else -> HAPPY
            }
        }
    }
}
