package com.digitalpet.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Every foreground the app draws on every surface it draws it on, against WCAG.
 *
 * **This exists because a design system caught what a year of looking did not.**
 * `primary` `#F5A623` on the light creams measures **1.9:1** against a 4.5:1
 * requirement, so every gold label and gold icon on a light surface had been
 * failing since the light theme shipped. Nobody saw it, because it does not look
 * broken — it looks like a slightly pale label — and no test in this project
 * touched colour at all.
 *
 * **A contrast ratio is arithmetic, which makes it exactly the kind of visual
 * property that does not need eyes.** The rest of the visual layer genuinely
 * does; this part never did, and leaving it to a reviewer was the mistake.
 *
 * ### The thresholds, and why two of them
 *
 * - **4.5:1** for text under 18pt, which is all of it. This app has no hero type.
 * - **3:1** for icons, borders and other non-text indicators — a bar's fill
 *   against its track, a focused outline, a selected radio.
 *
 * ### What it deliberately does not cover
 *
 * `unknown` and the bubble timestamp are alpha-composited and *meant* to be
 * quiet: they say "we have not been told" and "this is metadata". Holding them
 * to body-text contrast would be reading the rule rather than the reason.
 */
class ContrastTest {

    // ---- WCAG 2.1 relative luminance and contrast -------------------------

    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(fg: Color, bg: Color): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /**
     * Rounded to two decimals before comparing, as every contrast tool reports
     * it. Without that, `thriving` on the light background fails at 4.4999 —
     * which is not a finding, it is a float.
     */
    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) {
        val r = "%.2f".format(ratio(fg, bg)).toDouble()
        assertTrue("$name is $r:1, needs $min:1", r >= min)
    }

    private val TEXT = 4.5
    private val UI = 3.0

    /*
     * EVERYTHING BELOW READS THE SCHEMES, NOT THE CONSTANTS BEHIND THEM.
     *
     * It used to read `Color.kt` directly — `LightOnSurfaceVariant`, `PetGold`,
     * `M3LightOutlineVariant` — and that made a whole class of change invisible
     * to it: rewiring a role in `Theme.kt`, or a `PetColors` entry, to a
     * different constant would leave every assertion green while the app changed
     * colour. **A mutation caught it**: swapping `PetColors.track` back to the
     * cream it replaced passed, which is the one thing this file exists to stop.
     *
     * A component receives `MaterialTheme.colorScheme.x` and `PetTheme.colors.y`,
     * so those are what is measured. `DigitalPetTheme` hands out exactly these
     * two pairs of objects.
     */
    private val light = LightColorScheme
    private val dark = DarkColorScheme
    private val lightPet = LightPetColors
    private val darkPet = DarkPetColors

    // ---- light: the scheme the failure was in ------------------------------

    /** Every surface a foreground can land on in the light scheme. */
    private val lightSurfaces = listOf(
        "background" to light.background,
        "surface" to light.surface,
        "surfaceVariant" to light.surfaceVariant,
    )

    @Test
    fun `light body text is readable on every surface`() {
        lightSurfaces.forEach { (n, bg) ->
            assertContrast("onSurface on light $n", light.onSurface, bg, TEXT)
        }
    }

    @Test
    fun `THE GOLD THAT CARRIES TEXT is readable on every light surface`() {
        // The bug this file was written for. LightGoldText replaced `primary`
        // wherever gold is a foreground; if someone reverts that, this fails.
        lightSurfaces.forEach { (n, bg) ->
            assertContrast("accentText on light $n", lightPet.accentText, bg, TEXT)
        }
    }

    @Test
    fun `the raw brand gold still fails as light text, which is why accentText exists`() {
        // Pinned deliberately. It documents WHY there are two golds, and it will
        // start failing the day someone lightens the creams enough to make
        // PetGold usable — at which point the second token can go.
        val worst = lightSurfaces.minOf { (_, bg) -> ratio(light.primary, bg) }
        assertTrue(
            "light `primary` now reaches %.2f:1 as text — reconsider accentText".format(worst),
            worst < TEXT,
        )
    }

    @Test
    fun `THE MUTED SET - every secondary and status colour clears 4-5 on every surface`() {
        /*
         * WAS THE DEBT THIS FILE FOUND ON ITS FIRST RUN, and is now the
         * assertion. Six pairs sat under the bar, all the same shape: a muted
         * foreground on one of the warmer creams.
         *
         *                       background  surface  surfaceVariant
         *   onSurfaceVariant          3.52     3.84            3.31
         *   thriving                  4.50✓    4.91✓           4.23
         *   error                     4.46     4.87✓           4.20
         *
         * All three were darkened together on 2026-08-09 — DESIGN.md §7.7's
         * option 2, chosen over moving `onSurfaceVariant` alone. **The three
         * kept their relationship**, which is the part a per-colour fix would
         * have lost: they now land on the same profile as each other, 4.8 on
         * background, 5.2 on surface, 4.5 on surfaceVariant, so the hierarchy
         * between them is what it was and only the floor moved.
         *
         * `onSurfaceVariant` was the one that mattered — §6.1 says it "carries
         * more of this app's look than primary does" — and it got a NEW value
         * rather than the `LightOnSurfaceStrong` that already existed, because
         * reusing that would have collapsed two of the palette's three
         * on-surface tiers into one. See Color.kt.
         */
        lightSurfaces.forEach { (n, bg) ->
            assertContrast("onSurfaceVariant on light $n", light.onSurfaceVariant, bg, TEXT)
            assertContrast("thriving on light $n", lightPet.thriving, bg, TEXT)
            assertContrast("error on light $n", light.error, bg, TEXT)
        }
    }

    @Test
    fun `the muted set stays a set - none of the three drifts away from the others`() {
        /*
         * The three were darkened *together*, and this is what stops the next
         * change moving one of them on its own. Nothing above would notice: a
         * much darker `error` would pass 4.5 comfortably and would also stop
         * looking like it belonged beside the other two.
         *
         * The bound is loose on purpose — it is a claim about them being the
         * same kind of colour, not about them being interchangeable.
         */
        lightSurfaces.forEach { (n, bg) ->
            val ratios = listOf(light.onSurfaceVariant, lightPet.thriving, light.error)
                .map { ratio(it, bg) }
            val spread = ratios.max() - ratios.min()
            assertTrue(
                "the muted set spans %.2f on light %s — they are no longer one set".format(spread, n),
                spread <= 0.75,
            )
        }
    }

    @Test
    fun `light content on its own container is readable`() {
        assertContrast("onPrimary on primary", light.onPrimary, light.primary, TEXT)
        assertContrast("onErrorContainer on errorContainer",
            light.onErrorContainer, light.errorContainer, TEXT)
        assertContrast("onPrimaryContainer on primaryContainer",
            light.onPrimaryContainer, light.primaryContainer, TEXT)
    }

    @Test
    fun `a filled bar can be read against its track, in both schemes`() {
        /*
         * ALSO FOUND ON THE FIRST RUN, and the harder half of it. Gold on the
         * usage bar's `outlineVariant` track was **1.38:1** against the 3:1 a
         * non-text indicator wants, and gold on a meter's `surfaceVariant`
         * segment was 1.72 — the two bars in this app were on different track
         * colours and both were pale golds against pale creams.
         *
         * Fixed on 2026-08-09 with `PetColors.track`, one track for both bars,
         * dark in the light scheme. **A lighter track cannot work**: gold is
         * itself light, so a track moving towards it passes *through* it — 1.38
         * falls to about 1.01 before climbing the far side. There was no quiet
         * light-scheme answer to find, which is worth asserting rather than
         * remembering, so the far-side check below is here too.
         */
        assertContrast("primary fill on the light track", light.primary, lightPet.track, UI)
        assertContrast("primary fill on the dark track", dark.primary, darkPet.track, UI)
    }

    @Test
    fun `the light track had to go past the gold, not towards it`() {
        /*
         * The reason the fix looks drastic, stated as arithmetic rather than
         * remembered as an anecdote.
         *
         * Darkening the old cream track makes the fill HARDER to see before it
         * makes it easier: the track passes through gold's own luminance on the
         * way down. So "darken it a little" was never an option — the ladder
         * runs 1.38 (old track) → 1.05 at its worst → 3.74 (new track), and only
         * the far side clears 3.
         *
         * The old track is not even the bottom, which is the part that is easy
         * to get backwards and which an earlier version of this test asserted
         * wrongly. `outline` #C3BAA0 sits between the two and is worse than
         * either.
         */
        val ladder = listOf(
            "old track" to light.outlineVariant,
            "outline, between them" to light.outline,
            "new track" to lightPet.track,
        ).map { (n, c) -> n to ratio(light.primary, c) }

        val (worstName, worst) = ladder.minBy { it.second }
        assertTrue(
            "the worst rung is $worstName at %.2f — the dip has moved".format(worst),
            worstName == "outline, between them",
        )
        assertTrue(
            "a mid-tone track reaches %.2f:1, better than the cream one it replaced"
                .format(worst),
            worst < ladder.first().second,
        )
        assertTrue(
            "only the far side of the dip clears $UI:1",
            ladder.last().second >= UI,
        )
    }

    // ---- dark: the scheme that was always fine, pinned so it stays that way --

    private val darkSurfaces = listOf(
        "background" to dark.background,
        "surface" to dark.surface,
        "surfaceVariant" to dark.surfaceVariant,
    )

    @Test
    fun `dark text is readable on every surface`() {
        darkSurfaces.forEach { (n, bg) ->
            assertContrast("onSurface on dark $n", dark.onSurface, bg, TEXT)
            assertContrast("gold on dark $n", dark.primary, bg, TEXT)
        }
    }

    @Test
    fun `dark status colours are readable where they are used`() {
        listOf("thriving" to darkPet.thriving, "error" to dark.error).forEach { (name, fg) ->
            darkSurfaces.forEach { (n, bg) -> assertContrast("$name on dark $n", fg, bg, TEXT) }
        }
    }

    @Test
    fun `gold needs no second token in dark, which is why it has none`() {
        // The mirror of the light assertion above: PetGold passes as text here,
        // so PetColors.accentText is PetGold in the dark scheme. If this ever
        // fails, dark needs its own readable gold too.
        darkSurfaces.forEach { (n, bg) ->
            assertContrast("accentText as dark text on $n", darkPet.accentText, bg, TEXT)
        }
    }
}
