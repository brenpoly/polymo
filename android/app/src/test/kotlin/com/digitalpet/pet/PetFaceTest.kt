package com.digitalpet.pet

import com.digitalpet.ble.PetProtocol
import com.digitalpet.ui.theme.PetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PetFace]'s rule, and the invariants every [PetFaceSet] has to satisfy.
 *
 * This file used to pin the face geometry as literals, because the pet drew its
 * face in C and the phone drew it in Compose and nothing compiled both — so the
 * two could drift indefinitely and the only symptom was two screens held side by
 * side showing different pets. **Generation replaced that job**: all three
 * surfaces now come out of `design-system/faces/`, and `FaceSyncTest` checks the
 * generator was actually run.
 *
 * What is left here is the part generation cannot give you. [PetFace.of] is a
 * mirror of `pet_sim_baseline_mood()` and stays hand-written on both sides,
 * because the design system owns what a face LOOKS like and the code stays
 * authoritative about what it MEANS. And a set is free to choose any numbers it
 * likes, but not to make the dead pet smile or sad and sick look alike — those
 * are checked across every set, so a new theme cannot quietly break them.
 */
class PetFaceTest {

    private fun condition(
        satiety: Int,
        happiness: Int,
        dead: Boolean = false,
        sick: Boolean = false,
    ) = PetProtocol.Condition(
        satiety = satiety,
        happiness = happiness,
        careMistakes = 0,
        dead = dead,
        sick = sick,
        calling = false,
    )

    // ---- the rule, in the firmware's order ---------------------------------

    @Test
    fun `DEAD OUTRANKS EVERYTHING, including sickness and a full belly`() {
        // pet_sim_baseline_mood starts here, and the firmware's reason is worth
        // keeping: a corpse with an opinion about lunch is not a design.
        assertEquals(PetFace.DEAD, PetFace.of(condition(4, 4, dead = true)))
        assertEquals(PetFace.DEAD, PetFace.of(condition(4, 4, dead = true, sick = true)))
        assertEquals(PetFace.DEAD, PetFace.of(condition(0, 0, dead = true)))
    }

    @Test
    fun `SICK OUTRANKS THE SCORES, because it is the one the user can act on`() {
        assertEquals(PetFace.SICK, PetFace.of(condition(4, 4, sick = true)))
        assertEquals(PetFace.SICK, PetFace.of(condition(0, 0, sick = true)))
    }

    @Test
    fun `A ZERO ON ANY NEED NEVER SMILES, whatever the sum says`() {
        /*
         * The floor, and it is not redundant with the sum below. Satiety 0 with
         * happiness 4 totals 4, which lands in NEUTRAL — a contented face on a
         * starving pet. The firmware calls this out specifically and a mirror
         * that dropped it would be wrong in the case that matters most.
         */
        assertEquals(PetFace.SAD, PetFace.of(condition(0, 4)))
        assertEquals(PetFace.SAD, PetFace.of(condition(4, 0)))
        assertNotEquals(PetFace.NEUTRAL, PetFace.of(condition(0, 4)))
    }

    @Test
    fun `the sum decides the rest, at the firmware's boundaries`() {
        // <=2 SAD, <=5 NEUTRAL, else HAPPY. The boundaries are pinned because
        // "at" and "past" are one character apart in C and in Kotlin.
        assertEquals(PetFace.SAD, PetFace.of(condition(1, 1)))      // 2
        assertEquals(PetFace.NEUTRAL, PetFace.of(condition(2, 1)))  // 3
        assertEquals(PetFace.NEUTRAL, PetFace.of(condition(3, 2)))  // 5
        assertEquals(PetFace.HAPPY, PetFace.of(condition(3, 3)))    // 6
        assertEquals(PetFace.HAPPY, PetFace.of(condition(4, 4)))    // 8
    }

    @Test
    fun `nothing heard is not a face, and is not a guess either`() {
        // The surfaces dim rather than inventing a mood — §5.0 rule 2 as a
        // picture. NEUTRAL is what they dim.
        assertEquals(PetFace.NEUTRAL, PetFace.of(null))
    }

    // ---- what EVERY set must be true of ---------------------------------
    //
    // These were assertions about one hard-coded table. They are properties of
    // every set now, which is the check a theme system actually needs: the
    // numbers are free to change, the meanings are not.

    @Test
    fun `every set draws every face the app can show`() {
        // A set missing one would throw at draw time, on whichever screen
        // happened to reach that mood first — most likely the dead one.
        PetFaceSets.all.forEach { set ->
            PetFace.entries.forEach { face ->
                assertNotNull("${set.id} has no $face", set[face])
            }
        }
    }

    @Test
    fun `THE DEAD FACE HAS NO MOUTH, in every set`() {
        // The blink and the breathe are what make the pet look alive, so their
        // absence is what says it is not. Everything else has a mouth.
        PetFaceSets.all.forEach { set ->
            assertTrue("${set.id} gave the dead pet a mouth", !set[PetFace.DEAD].hasMouth)
            PetFace.entries.filter { it != PetFace.DEAD }.forEach {
                assertTrue("${set.id}: $it lost its mouth", set[it].hasMouth)
            }
        }
    }

    @Test
    fun `SAD AND SICK ARE NOT VARIATIONS ON EACH OTHER, in every set`() {
        /*
         * The firmware is emphatic: the two mean different things and demand
         * different actions, so they must not be confusable across a room. A
         * new set could easily satisfy every other test here and still make
         * them near-identical, which would leave the user unable to tell "feed
         * me" from "put the phone down".
         */
        PetFaceSets.all.forEach { set ->
            val sad = set[PetFace.SAD]
            val sick = set[PetFace.SICK]
            assertNotEquals("${set.id}: sad and sick share an eye width",
                sad.eyeWidth, sick.eyeWidth)
            assertNotEquals("${set.id}: sad and sick share an eye height",
                sad.eyeHeight, sick.eyeHeight)
        }
    }

    @Test
    fun `no two faces in a set are drawn the same`() {
        // A face that duplicated another would be a state the user cannot see.
        PetFaceSets.all.forEach { set ->
            val all = PetFace.entries.map { set[it] }
            assertEquals("${set.id} draws two faces identically",
                PetFace.entries.size, all.toSet().size)
        }
    }

    @Test
    fun `CLASSIC IS STILL THE FIRMWARE'S OWN NUMBERS`() {
        /*
         * The migration check, kept. `classic` is a transcription of
         * apply_mood() as it stood at protocol v8, and it exists so the
         * table-driven pet is provably the same pet. If someone "tidies" these
         * they have changed the pet everyone already owns.
         */
        val c = PetFaceSets.CLASSIC
        // Compared field by field rather than as whole objects: the geometry
        // gains fields as the pet gets more expressive, and a test that has to
        // be rewritten every time one is added stops being read.
        //
        // The EYES are untouched from apply_mood() at protocol v8 — they carry
        // most of the expression and are the part that says "same pet".
        // HAPPY is the exception and is checked below: its eyes became a
        // stroke, so its "height" is now a line weight rather than an opening.
        assertEquals(74, c[PetFace.HAPPY].eyeWidth)
        assertEquals(listOf(74, 96), listOf(c[PetFace.NEUTRAL].eyeWidth, c[PetFace.NEUTRAL].eyeHeight))
        assertEquals(listOf(74, 62), listOf(c[PetFace.SAD].eyeWidth, c[PetFace.SAD].eyeHeight))
        assertEquals(listOf(74, 8), listOf(c[PetFace.DEAD].eyeWidth, c[PetFace.DEAD].eyeHeight))

        /*
         * SICK IS THE SECOND DELIBERATE DEPARTURE, recorded here for the same
         * reason the mouth below is: so it cannot read as drift.
         *
         * apply_mood()'s sick face was two thin lids, 52x22 and 52x30. Nothing
         * was wrong with it except what it looks like from across a room, which
         * is two thin horizontal lines — and so are sleepy, asleep and dead.
         * **The one state the user is supposed to ACT on looked like the three
         * they cannot do anything about.** The eyes are spirals now, a shape no
         * other face wears at any size, and the two numbers mean something
         * different in consequence: an outer diameter and a stroke, not a box.
         * See design-system/faces/README.md.
         */
        assertTrue("classic's sick face stopped spiralling", c[PetFace.SICK].isSpiral)
        assertEquals(listOf(76, 10), listOf(c[PetFace.SICK].eyeWidth, c[PetFace.SICK].eyeHeight))
        assertEquals(64, c.eyeOffsetX)
        assertEquals(-56, c.eyeOffsetY)
        assertEquals(40, c.mouthOffsetY)

        /*
         * THE MOUTH IS THE ONE DELIBERATE DEPARTURE. Classic's happy face was a
         * flat 150x46 bar and its sad face a flat 74x12 one — structurally fine,
         * and they did not smile or frown. Once the states carry rules, "happy"
         * has to actually mean happy, so classic curves. Recorded here as an
         * intended change rather than left to look like drift.
         */
        assertTrue("classic no longer smiles", c[PetFace.HAPPY].mouthBulge > 0)

        /*
         * AND ITS EYES CURVE UP. apply_mood()'s own comment for this face has
         * always read "squinted eyes + a big grin — the classic ^ ^", but a
         * filled lozenge cannot be a "^" however flat it gets, so for years it
         * was drawn as a squashed capsule. The eyes are a stroke now and the
         * comment is finally true.
         *
         * Negative bulge curves UP, so the assertion is the sign, not the
         * magnitude — a set may squint as hard as it likes.
         */
        assertTrue("classic's happy eyes stopped curving", c[PetFace.HAPPY].eyeArcRadius > 0)
        assertTrue("classic's happy eyes curve the wrong way",
            c[PetFace.HAPPY].eyeArcStart > 180)
        assertTrue("classic no longer frowns", c[PetFace.SAD].mouthBulge < 0)
        assertEquals(0, c[PetFace.NEUTRAL].mouthBulge)
    }

    // ---- motion -------------------------------------------------------------

    @Test
    fun `A DEAD FACE DOES NOT MOVE, in any set`() {
        /*
         * The blink and the breathe are what make the pet look alive, so their
         * absence is the whole message. This used to be a `dead || asleep`
         * check inside three renderers; it is data now, which means a set could
         * accidentally give a corpse a heartbeat. The generator refuses that
         * too — belt and braces, because it is the one motion bug that would
         * undermine the pet's only irreversible state.
         */
        PetFaceSets.all.forEach { set ->
            assertEquals("${set.id}: the dead face breathes", 0, set[PetFace.DEAD].breatheMs)
            assertEquals("${set.id}: the dead face blinks", 0, set[PetFace.DEAD].blinkMs)
        }
    }

    @Test
    fun `EVERY LIVING FACE MOVES`() {
        // The mirror of the rule above, and the reason it is worth asserting: a
        // still living pet reads as broken hardware (DESIGN.md §6).
        PetFaceSets.all.forEach { set ->
            PetFace.entries.filter { it != PetFace.DEAD }.forEach { face ->
                assertTrue("${set.id}/$face does not breathe", set[face].breatheMs > 0)
                assertTrue("${set.id}/$face never blinks", set[face].blinkMs > 0)
            }
        }
    }

    @Test
    fun `A SAD PET LOOKS DOWN, in every set`() {
        /*
         * Gaze is the cheapest expressiveness there is — two integers, no new
         * objects, no extra pixels — and it does more for the sad face than any
         * amount of mouth. A pet staring straight ahead with a frown reads as
         * annoyed; the same face looking down and away reads as sad.
         *
         * Positive gazeY is DOWN, matching the screen's own axis.
         */
        PetFaceSets.all.forEach { set ->
            assertTrue("${set.id}: the sad face does not look down",
                set[PetFace.SAD].gazeY > 0)
        }
    }

    @Test
    fun `THE SICK FACE HAS UNEVEN EYES, in every set`() {
        // The one place asymmetry is load-bearing rather than decorative: two
        // identical spirals read as a logo, and two that differ read as woozy.
        // It was one heavier lid before the eyes were spirals; the rule
        // outlived the shape, which is why it is stated as "uneven" and
        // measured on whatever the eyes currently are.
        PetFaceSets.all.forEach { set ->
            val g = set[PetFace.SICK]
            assertNotEquals("${set.id}: the sick face's eyes match",
                g.eyeHeight, g.rightEyeHeight)
        }
    }

    @Test
    fun `THE SICK FACE SPIRALS, and nothing else does`() {
        /*
         * **The reason this state got a new shape rather than new numbers.**
         *
         * Sick was two thin lids — and so is sleepy, so is asleep, and so is
         * dead. Four states, one silhouette, and the only one of the four the
         * user can act on was indistinguishable from the three they cannot.
         * A spiral is worn by nothing else at any size, which is the whole
         * property, so this asserts the exclusivity and not just the presence.
         */
        PetFaceSets.all.forEach { set ->
            assertTrue("${set.id}: the sick face does not spiral",
                set[PetFace.SICK].isSpiral)
            PetFace.entries.filter { it != PetFace.SICK }.forEach { face ->
                assertFalse("${set.id}/$face spirals; only SICK may",
                    set[face].isSpiral)
            }
        }
    }

    @Test
    fun `A SPIRAL TURNS, or it is not saying anything`() {
        /*
         * A spiral that has stopped is a pattern the pet happens to be wearing.
         * The rotation is what reads as dizzy, so a `spinMs` of 0 on the sick
         * face would be the shape shipped with its meaning removed — the same
         * class of failure as the happy face that did not smile.
         */
        PetFaceSets.all.forEach { set ->
            assertTrue("${set.id}: the sick face's spiral does not turn",
                set[PetFace.SICK].spinMs > 0)
            PetFace.entries.filter { it != PetFace.SICK }.forEach { face ->
                assertEquals("${set.id}/$face turns, and nothing but a spiral does",
                    0, set[face].spinMs)
            }
        }
    }

    @Test
    fun `A SPIRAL HAS GAPS BETWEEN ITS TURNS, in every set`() {
        /*
         * The one way this shape fails silently: wind it too tightly or stroke
         * it too thickly and the turns weld into a filled disc — which is a
         * blank stare, and says less than the two lids it replaced.
         *
         * It is arithmetic, so it never needed eyes. The generator refuses it
         * at the source; this is the same rule measured on what compiled.
         */
        PetFaceSets.all.forEach { set ->
            val g = set[PetFace.SICK]
            listOf(
                "left" to (g.eyeWidth to g.eyeHeight),
                "right" to (g.rightEyeWidth to g.rightEyeHeight),
            ).forEach { (which, dims) ->
                val (width, stroke) = dims
                val gap = (width / 2.0) / g.eyeSpiralTurns
                assertTrue(
                    "${set.id}: the $which spiral fills in — $gap between turns, " +
                        "stroke $stroke",
                    gap >= stroke * 1.5,
                )
            }
        }
    }

    @Test
    fun `THE TWO SPIRALS WIND OPPOSITE WAYS, in every set`() {
        /*
         * The sick face's asymmetry, which used to be one heavier lid. Two
         * identical spirals read as a logo; two counter-wound ones read as
         * woozy.
         *
         * Both windings are STORED rather than derived by negating x, and this
         * checks the stored pair really is a mirror. The alternative — one
         * table plus a "the right one is flipped" rule — would put that rule in
         * five renderers separately, which is exactly how every arc on every
         * surface that took a path came to bend the wrong way until 2026-08-11.
         */
        PetFaceSets.all.forEach { set ->
            val g = set[PetFace.SICK]
            val left = g.spiral(0) ?: throw AssertionError("${set.id}: no left spiral")
            val right = g.spiral(1) ?: throw AssertionError("${set.id}: no right spiral")
            assertEquals("${set.id}: the two spirals are different lengths",
                left.size, right.size)
            // 2 turns at 24 steps is 49 points, and a spiral of four points is
            // a diamond — so this also catches a table that generated but is
            // too coarse to be a curve.
            assertTrue("${set.id}: the spiral has too few points to be one",
                left.size >= 2 * (PetSpirals.STEPS_PER_TURN * g.eyeSpiralTurns))
            left.indices.forEach { i ->
                val want = if (i % 2 == 0) -left[i] else left[i]
                assertEquals(
                    "${set.id}: the right spiral is not the left one mirrored, at $i",
                    want, right[i],
                )
            }
            /*
             * And it is a SPIRAL and not a circle: it starts at the centre and
             * ends on the rim, which is the property `arc` cannot have.
             */
            assertEquals("${set.id}: the spiral does not start at its centre",
                listOf(0, 0), listOf(left[0], left[1]))
            val rim = kotlin.math.hypot(
                left[left.size - 2].toDouble(), left[left.size - 1].toDouble(),
            )
            assertEquals("${set.id}: the spiral does not reach its rim",
                PetSpirals.UNIT.toDouble(), rim, 1.0)
        }
    }

    @Test
    fun `THE EYES STAY ON THE FACE`() {
        // A gaze far enough off centre stops reading as a glance and starts
        // reading as a rendering fault. The generator refuses beyond 40px; this
        // is the same limit checked against what actually compiled.
        PetFaceSets.all.forEach { set ->
            PetFace.entries.forEach { face ->
                val g = set[face]
                assertTrue("${set.id}/$face gazeX ${g.gazeX}", kotlin.math.abs(g.gazeX) <= 40)
                assertTrue("${set.id}/$face gazeY ${g.gazeY}", kotlin.math.abs(g.gazeY) <= 40)
            }
        }
    }

    @Test
    fun `A SICK PET MOVES DIFFERENTLY FROM A HAPPY ONE`() {
        /*
         * The point of per-state motion. Every mood used to breathe on the same
         * 1900 ms, so a dying pet and a delighted one were distinguishable only
         * by shape. Shallow and fast reads as ill before you have looked at the
         * mouth — and if a set ever collapses the two back together, that
         * expressiveness is silently gone with nothing else to catch it.
         */
        PetFaceSets.all.forEach { set ->
            val sick = set[PetFace.SICK]
            val happy = set[PetFace.HAPPY]
            assertTrue("${set.id}: sick does not breathe faster than happy",
                sick.breatheMs < happy.breatheMs)
            assertTrue("${set.id}: sick does not breathe shallower than happy",
                (sick.breatheTo - sick.breatheFrom) < (happy.breatheTo - happy.breatheFrom))
        }
    }

    @Test
    fun `THE DEFAULT SET IS THE FIRMWARE'S FALLBACK`() {
        // The firmware falls back to table index 0 when NVS names a set it does
        // not have, and the generator sorts `classic` first for exactly that.
        // If these two ever disagree, a pet with no stored choice and an app
        // with no answer yet would draw different faces.
        assertEquals("classic", PetFaceSets.default.id)
        assertEquals("classic", PetFaceSets.all.first().id)
    }

    @Test
    fun `a set id the app does not know is null, not a crash`() {
        // A pet running newer firmware will name a set this build has never
        // heard of. The surfaces fall back; they must not throw.
        assertNull(PetFaceSets.byId("no-such-set"))
        assertNull(PetFaceSets.byId(null))
        assertEquals(PetFaceSets.CLASSIC, PetFaceSets.byId("classic"))
    }

    // ---- the stage scale, which the phone did not have at all ---------------

    @Test
    fun `THE PET GROWS, and the phone now knows by how much`() {
        // stage_pct in apply_mood(). The app drew every stage the same size
        // until 2026-08-09, so a hatchling looked full-grown in the hand and
        // small on the shelf.
        val c = PetFaceSets.CLASSIC
        assertEquals(0.55f, c.stageScale(PetProtocol.Stage.EGG), 0.001f)
        assertEquals(0.70f, c.stageScale(PetProtocol.Stage.CHILD), 0.001f)
        assertEquals(0.85f, c.stageScale(PetProtocol.Stage.TEEN), 0.001f)
        assertEquals(1.00f, c.stageScale(PetProtocol.Stage.ADULT), 0.001f)
    }

    @Test
    fun `an unknown stage draws full size rather than shrinking to an egg`() {
        // Null means we have not been told. Drawing an egg would be asserting
        // the pet is newly hatched, which is the §5.0 rule 2 failure in its most
        // misleading direction: the surfaces that show this dim it instead.
        assertEquals(1.00f, PetFaceSets.CLASSIC.stageScale(null), 0.001f)
    }

    @Test
    fun `the layout offsets are the firmware's own`() {
        // Quoted from the eye loop and the two lv_obj_align calls. The app used
        // a centred column with an invented gap before this.
        assertEquals(64, PetFaceSets.CLASSIC.eyeOffsetX)
        assertEquals(-56, PetFaceSets.CLASSIC.eyeOffsetY)
        assertEquals(40, PetFaceSets.CLASSIC.mouthOffsetY)
    }

    @Test
    fun `THE SCREEN IS THE ROTATED PANEL, not the panel`() {
        /*
         * `PET_SCREEN_W`/`PET_SCREEN_H` in pet-esp32.c, which are BSP_LCD_V_RES
         * and BSP_LCD_H_RES swapped because the firmware rotates the display 90°.
         *
         * This is pinned as literals for the same reason the geometry is: the
         * pet's panel is wired 368x448 and every instinct is to write those two
         * numbers down in that order. Doing so drew the app's face 22% too large
         * for its panel and nothing failed.
         */
        assertEquals(448, PetFace.SCREEN_WIDTH)
        assertEquals(368, PetFace.SCREEN_HEIGHT)
    }

    @Test
    fun `THE APP'S PANEL IS THE SHAPE OF THE PET'S SCREEN, within 5 percent`() {
        /*
         * The claim DESIGN.md and the token files both make about the 240x200
         * panel, finally checked rather than asserted — and it had been false in
         * the comments, which named the unrotated 368x448 (0.82) while the mock
         * was drawn at 1.20.
         *
         * A tolerance rather than equality because 240x200 is a round pair of
         * design numbers, not the device's ratio to four places. 5% is tight
         * enough to fail on the portrait reading, which is 46% out.
         */
        val screen = PetFace.SCREEN_WIDTH.toFloat() / PetFace.SCREEN_HEIGHT
        val panel = PetSize.petPanelWidth.value / PetSize.petPanelHeight.value
        assertEquals(screen, panel, screen * 0.05f)
    }
}
