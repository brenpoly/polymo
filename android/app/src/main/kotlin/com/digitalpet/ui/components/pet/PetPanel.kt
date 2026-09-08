package com.digitalpet.ui.components.pet

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.digitalpet.ble.PetProtocol
import com.digitalpet.pet.PetFace
import com.digitalpet.pet.PetFaceSet
import com.digitalpet.pet.PetFaceSets
import com.digitalpet.pet.PetSpirals
import com.digitalpet.ui.theme.PetRadius
import com.digitalpet.ui.theme.PetSize
import com.digitalpet.ui.theme.PetSpacing
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity

/**
 * The pet, drawn as the physical thing rather than as an app illustration.
 *
 * **IT DOES NOT FOLLOW THE PHONE'S THEME, deliberately.** This is a picture of a
 * switched-on AMOLED, so its colours come from the pet's own face set and not
 * from MaterialTheme — otherwise the pet would change colour when the user
 * changed a phone setting, which is the confusion between "the pet" and "the
 * app" that DESIGN.md §5.0 rule 1 exists to prevent. A set changes the pet on
 * every surface at once, which is the opposite thing and is the point. 240×200 at 34dp radius keeps the Waveshare's proportion as
 * the pet actually shows it — **448×368, the panel rotated**, which is 1.22
 * against this panel's 1.20. Portrait would have been 0.82; the mocks were
 * never portrait — see [PetSize.petPanelWidth] and [PetFace.SCREEN_WIDTH].
 *
 * The blink is the same 5.4 s cycle the firmware uses, so the two surfaces agree
 * about what the pet is doing rather than each inventing a rhythm.
 */
@Composable
fun PetPanel(
    condition: PetProtocol.Condition?,
    listening: Boolean,
    thinking: Boolean,
    /**
     * The style the pet is wearing. Defaults to the same set the firmware falls
     * back to, so a panel drawn before the pet has said anything shows the face
     * it is most likely actually wearing rather than a guess.
     */
    set: PetFaceSet = PetFaceSets.default,
) {
    /*
     * THE FACE FOLLOWS THE PET, as of 2026-08-09.
     *
     * This drew one smile and a flat line for dead, so the panel showed a
     * contented pet while the care card underneath said "Starving and
     * miserable." Two surfaces, one screen, disagreeing.
     *
     * `PetFace` mirrors the firmware's own rule and geometry, so this is the
     * same face the pet is wearing rather than an app-side interpretation of
     * it — see there for why the phone can know, and for the transient
     * expressions it deliberately does not try to.
     */
    val face = PetFace.of(condition)
    val g = set[face]

    /*
     * ONE SCALE FROM THE DEVICE'S UNITS, plus the pet's own growth.
     *
     * Every number in `PetFace` is in device pixels, so a single factor converts
     * the lot and the proportions are the device's rather than a set of
     * separately-chosen phone numbers. The app had invented its own eye gap, eye
     * radius and face spacing; they are all derived now.
     *
     * The divisor is the **screen** width, 448, not the panel's 368: the pet
     * rotates its display, so 368 is the edge nobody looks along.
     *
     * `stageScale` is the pet growing. An egg's face is 55% of an adult's on the
     * device and was full size here, so a hatchling looked full-grown in the
     * hand and small on the shelf. It multiplies the SIZES only — the offsets
     * and radii are untouched, so the face shrinks inside a panel that does not.
     */
    val scale = PetSize.petPanelWidth.value / PetFace.SCREEN_WIDTH
    val grow = set.stageScale(condition?.stage)
    fun pos(units: Int) = (units * scale).dp
    fun size(units: Int) = (units * scale * grow).dp

    val transition = rememberInfiniteTransition(label = "pet")
    val open = g.eyeHeight * scale * grow
    val openRight = g.rightEyeHeight * scale * grow
    val shut = PetSize.eyeClosed.value
    val eyeHeight by transition.animateFloat(
        initialValue = open,
        targetValue = open,
        animationSpec = infiniteRepeatable(
            /*
             * THE RHYTHM IS THE FACE'S OWN, from the set — the same numbers the
             * device animates on, so the two surfaces still agree about what
             * the pet is doing rather than each keeping its own clock.
             *
             * A blinkMs of 0 means this face does not blink, and holding the
             * eye open for a very long cycle is how that is expressed without a
             * second code path.
             */
            animation = keyframes {
                val cycle = if (g.blinkMs > 0) g.blinkMs else 600_000
                val shutFor = g.shutMs.coerceAtLeast(120)
                durationMillis = cycle
                open at 0
                open at (cycle - shutFor).coerceAtLeast(1)
                shut at (cycle - shutFor / 2).coerceAtLeast(2)
                open at (cycle - 1)
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "blink"
    )

    /* The right eye blinks from ITS open height, or a set that half-closes one
     * eye would see it snap to the left eye's size every time the pet blinked. */
    val rightEyeHeight by transition.animateFloat(
        initialValue = openRight,
        targetValue = openRight,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                val cycle = if (g.blinkMs > 0) g.blinkMs else 600_000
                val shutFor = g.shutMs.coerceAtLeast(120)
                durationMillis = cycle
                openRight at 0
                openRight at (cycle - shutFor).coerceAtLeast(1)
                shut at (cycle - shutFor / 2).coerceAtLeast(2)
                openRight at (cycle - 1)
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "blinkRight"
    )

    /*
     * THE BREATHE, which the phone did not have.
     *
     * `add_breathe()` translates the eyes and the mouth from -4 to +6 over
     * 1900 ms each way, eased. It is on the device because **idle motion is
     * identity** — DESIGN.md §6, "a still pet reads as broken" — and the app
     * drew a pet that only ever blinked.
     *
     * A dead pet does not breathe, and that is the firmware's rule too: the
     * blink and the breathe are what make the pet look alive, so their absence
     * is the message rather than an optimisation.
     */
    val breathe by transition.animateFloat(
        initialValue = g.breatheFrom * scale,
        targetValue = g.breatheTo * scale,
        animationSpec = infiniteRepeatable(
            animation = tween(
                g.breatheMs.coerceAtLeast(1),
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe"
    )
    /*
     * STILLNESS IS DATA, not a check for DEAD.
     *
     * A face with breatheMs of 0 does not move — which is how the dead face is
     * expressed now, and means a set can make any state as still as it likes
     * without this file knowing which states those are.
     */
    /*
     * THE SPIN, which only the sick face has.
     *
     * A spiral that has stopped is a pattern the pet happens to be wearing; it
     * is the turning that reads as dizzy. So this is not decoration, and the
     * generator refuses a spiral without a `spinMs` — see
     * `design-system/faces/README.md`.
     *
     * A spinMs of 0 is expressed as a cycle nobody will see the end of, the
     * same way a blinkMs of 0 is, rather than as a second code path.
     */
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                if (g.spinMs > 0) g.spinMs else 600_000,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin"
    )
    val drift = if (g.breatheMs <= 0) 0f else breathe
    // Read outside the Canvas: a DrawScope has its own `density` and no access
    // to the composition's colours.
    val mouthColor = set.mouthColor
    val density = LocalDensity.current.density

    Box(
        modifier = Modifier
            .width(PetSize.petPanelWidth)
            .height(PetSize.petPanelHeight)
            .clip(RoundedCornerShape(PetRadius.petPanel))
            .background(set.panelColor),
        contentAlignment = Alignment.Center
    ) {
        /*
         * POSITIONED THE WAY THE DEVICE POSITIONS THEM — eyes at ±64 and 56
         * above centre, mouth 40 below — rather than as a centred column with a
         * gap. The column looked equivalent and was not: it re-centred the whole
         * face whenever a mood changed the eye height, so the pet appeared to
         * shift up and down the panel as it felt things.
         */
        /*
         * TWO EYES THAT NEED NOT MATCH, and a gaze that moves both.
         *
         * `index 0` is the left. A set may give the right eye its own size —
         * on the sick face that is the smaller, heavier of its two spirals,
         * and an asymmetric face reads as queasy where a symmetric one reads
         * as a logo — and gazeY moves the pair off centre, which is most of
         * why the sad face now looks away rather than staring.
         */
        listOf(
            -set.eyeOffsetX + g.gazeX to (g.eyeWidth to eyeHeight),
            set.eyeOffsetX + g.gazeX to (g.rightEyeWidth to rightEyeHeight),
        ).forEachIndexed { side, pair ->
            val (x, dims) = pair
            val (eyeW, eyeH) = dims
            val spiral = g.spiral(side)
            if (spiral != null) {
                /*
                 * A SPIRAL EYE — the sick face, and the third form an eye takes.
                 *
                 * `eyeWidth` is the outer DIAMETER here and `eyeHeight` the
                 * stroke, the same reinterpretation a curved eye makes of its
                 * height. The points are the firmware's own table, in per-mille
                 * of that radius, so the pet in your hand winds the same way as
                 * the pet on the shelf — this file scales a curve rather than
                 * plotting one.
                 */
                val openH = if (side == 0) open else openRight
                val radius = eyeW * scale * grow / 2f
                val box = 2 * radius + openH
                /*
                 * The blink SQUASHES the spiral rather than thinning its
                 * stroke, which is what a lid closing over a pattern does. It
                 * reuses the blink the eye is already animating — `eyeHeight`
                 * runs from the open stroke down to shut — so the phone cannot
                 * drift onto its own blink schedule.
                 */
                val squash = if (openH > 0f) (eyeH / openH).coerceIn(0f, 1f) else 1f
                Canvas(
                    Modifier
                        .offset(x = pos(x), y = pos(set.eyeOffsetY + g.gazeY))
                        .graphicsLayer { translationY = drift }
                        .width(box.dp)
                        .height(box.dp)
                ) {
                    val mid = Offset(size.width / 2f, size.height / 2f)
                    val r = radius.dp.toPx()
                    val path = Path()
                    for (k in 0 until spiral.size / 2) {
                        val px = mid.x + spiral[2 * k] * r / PetSpirals.UNIT
                        val py = mid.y + spiral[2 * k + 1] * r / PetSpirals.UNIT
                        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    /*
                     * SCALE FIRST, THEN ROTATE — and the order is not the one it
                     * reads as. These calls post-multiply, so the LAST one is
                     * applied to the points FIRST: this squashes an already
                     * rotated spiral, which is the eye closing over it. The
                     * other order spins a permanently flattened spiral around
                     * its centre, which reads as a wobble. The device does the
                     * same two steps in the same order in spiral_rebuild().
                     */
                    withTransform({
                        scale(1f, squash, mid)
                        rotate(spin, mid)
                    }) {
                        drawPath(
                            path,
                            color = set.eyeColor,
                            style = Stroke(
                                width = openH.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
                return@forEachIndexed
            }
            if (g.eyeArcRadius > 0) {
                /*
                 * A CURVED EYE, drawn as a stroke rather than a squashed
                 * lozenge — the classic "^ ^". The radius and angles are the
                 * generator's, the same ones the device arcs on, so the two
                 * surfaces cannot curve differently.
                 */
                val box = (2 * g.eyeArcRadius * scale * grow)
                Canvas(
                    Modifier
                        .offset(
                            x = pos(x) - (box / 2).dp + (eyeW * scale * grow / 2).dp,
                            y = pos(set.eyeOffsetY + g.gazeY + g.eyeArcCenterDy) -
                                (box / 2).dp,
                        )
                        .graphicsLayer { translationY = drift }
                        .width(box.dp)
                        .height(box.dp)
                ) {
                    val stroke = eyeH.dp.toPx()
                    drawArc(
                        color = set.eyeColor,
                        startAngle = g.eyeArcStart.toFloat(),
                        sweepAngle = (g.eyeArcEnd - g.eyeArcStart).toFloat(),
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            stroke / 2f, stroke / 2f
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - stroke, size.height - stroke
                        ),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                return@forEachIndexed
            }
            Box(
                Modifier
                    .offset(x = pos(x), y = pos(set.eyeOffsetY + g.gazeY))
                    .graphicsLayer { translationY = drift }
                    .width(size(eyeW))
                    .height(eyeH.dp)
                    // The set's own eye radius. -1 is LV_RADIUS_CIRCLE on the
                    // device — as round as it goes, so during a blink the ends
                    // stay round as the eye flattens.
                    .clip(
                        if (set.eyeRadius < 0) PetRadius.pill
                        else RoundedCornerShape((set.eyeRadius * scale).dp)
                    )
                    .background(set.eyeColor)
            )
        }

        /*
         * NO MOUTH ON A DEAD PET, rather than a thin one. The firmware draws
         * none at all and says why: the blink and the breathe are what make the
         * pet look alive, so their absence is the message.
         *
         * THE MOUTH VARIES BY MOOD NOW, which it did not until face sets
         * landed. The gap recorded here was real: the firmware's mouths are
         * FILLED shapes, and a commit on 2026-08-09 stroked those numbers as an
         * arc and produced a hollow lozenge instead of a smile. It had to be
         * undone, and the mouth stayed a fixed arc afterwards.
         *
         * Generating both surfaces from one set is what closed it. This fills
         * the set's own rounded rectangle exactly as `apply_mood()` does, so
         * there is no second interpretation to get wrong — the smile is the
         * device's smile rather than the app's idea of one.
         */
        if (g.hasMouth) {
            /*
             * A BAR OR AN ARC, and the set decides which — a bulge of zero is a
             * straight capsule, which is what every mouth was before curves.
             *
             * Both draw from numbers the generator computed: the radius, the
             * two angles and where the circle's centre sits. Nothing here does
             * trigonometry, which is what stops this surface curving slightly
             * differently from the device or the notification.
             */
            if (g.isArc) {
                val box = (2 * g.arcRadius * scale * grow)
                Canvas(
                    Modifier
                        .offset(y = pos(set.mouthOffsetY + g.arcCenterDy))
                        .graphicsLayer { translationY = drift }
                        .width(box.dp)
                        .height(box.dp)
                ) {
                    drawArc(
                        color = mouthColor,
                        startAngle = g.arcStart.toFloat(),
                        sweepAngle = g.arcSweep.toFloat(),
                        useCenter = false,
                        style = Stroke(
                            width = g.mouthThickness * scale * grow * density,
                            cap = if (g.roundCap) StrokeCap.Round else StrokeCap.Butt,
                        ),
                    )
                }
            } else {
                Box(
                    Modifier
                        .offset(y = pos(set.mouthOffsetY))
                        .graphicsLayer { translationY = drift }
                        .width(size(g.mouthWidth))
                        .height(size(g.mouthThickness))
                        .clip(
                            if (g.roundCap) PetRadius.pill
                            else RoundedCornerShape(0.dp)
                        )
                        .background(mouthColor)
                )
            }
        }

        if (listening || thinking) {
            Text(
                text = if (listening) "Listening…" else "Thinking…",
                style = MaterialTheme.typography.labelSmall,
                // The set's text colour, not the theme's: this label sits ON the
                // pet's panel, so it belongs to the pet rather than to the app.
                color = set.textColor,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = PetSpacing.s12)
            )
        }
    }
}
