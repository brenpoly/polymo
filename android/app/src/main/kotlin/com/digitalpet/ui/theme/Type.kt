package com.digitalpet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.digitalpet.R

/**
 * The full Material 3 type scale, in the two faces it was always meant to have.
 *
 * **Baloo 2 and Quicksand are bundled as of 2026-08-09.** They were specified in
 * DESIGN.md §4, never supplied, and carried as open debt in §7.3 for months while
 * the app rendered in the platform default. The scale below is unchanged: the
 * sizes, line heights and letter-spacing are still Material's own, because §4
 * records typography as *unwritten* and inventing a scale while fixing a family
 * would bury a design decision inside a bug fix.
 *
 * ### Two faces, split by ROLE and not by size
 *
 * | Face | Carries | Why |
 * |---|---|---|
 * | **Baloo 2** ([Display]) | display, headline, title — anything that *names* something | Rounded and heavy-set. It is why the product reads as playful rather than as a settings app with a pet in it |
 * | **Quicksand** ([Sans]) | body and label — anything *read* rather than glanced at | Geometric with round terminals, so it agrees with Baloo 2 without competing |
 *
 * **The split is by role, which is the one thing easy to get backwards.** A 13sp
 * slot title is display; a 16sp settings subtitle is body. Sorting by size
 * instead would put a title in the reading face and make the pairing look like an
 * accident rather than a choice.
 *
 * ### One weight departure, and only on Quicksand
 *
 * Quicksand's 400 is visibly lighter than the platform default's at the same
 * size, so **body runs at Medium and labels at SemiBold** where this file
 * previously ran a step lighter. That preserves apparent weight rather than
 * changing it — the page should look the same darkness as before, in a different
 * face. Baloo 2 keeps the weights this file already had, `headlineLarge`'s
 * deliberate SemiBold included.
 *
 * ### Variable fonts, one file each
 *
 * Both faces ship as a single variable `.ttf` with a `wght` axis rather than four
 * static cuts apiece: 683 kB and 125 kB against roughly a megabyte, and every
 * weight is the real one rather than the nearest available. minSdk is 31, well
 * past the 26 that variable fonts need, so there is no fallback to keep working.
 *
 * **Neither face has an italic** — Baloo 2 is 400–800 upright, Quicksand 300–700
 * upright. Emphasis here is weight or colour, never a slant: a synthesised
 * oblique on a rounded face looks like a rendering fault.
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: Int) = Font(
    resId = resId,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Baloo 2 — the naming face. */
private val Display = FontFamily(
    variable(R.font.baloo2, 400),
    variable(R.font.baloo2, 500),
    variable(R.font.baloo2, 600),
    variable(R.font.baloo2, 700),
    variable(R.font.baloo2, 800),
)

/** Quicksand — the reading face. */
private val Sans = FontFamily(
    variable(R.font.quicksand, 300),
    variable(R.font.quicksand, 400),
    variable(R.font.quicksand, 500),
    variable(R.font.quicksand, 600),
    variable(R.font.quicksand, 700),
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Bold,
        fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp
    ),

    // SemiBold is deliberate and predates this file being completed.
    headlineLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp
    ),

    titleLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),

    // Medium, not Normal: Quicksand's 400 reads lighter than the platform
    // default's at the same size. See the note above — apparent weight is being
    // held, not changed.
    bodyLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp
    ),

    // SemiBold, one step up from this file's previous Medium, for the same reason.
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp
    ),
)

/**
 * The debug drawer's log view. Deliberately not part of the M3 scale — it is a
 * console, and console output that reflows is unreadable.
 *
 * Stays monospace and therefore stays in neither bundled face: rounded
 * geometric type has no fixed-width cut, and the design system keeps Roboto Mono
 * here for exactly that reason.
 */
val ConsoleTypography = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp
)
