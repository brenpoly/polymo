package com.digitalpet.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours Material 3 has no role for.
 *
 * M3's `ColorScheme` covers surfaces, brand and `error`, and that is genuinely
 * most of what an app needs — so this extension is deliberately small and only
 * holds meanings Material does not have a word for. Anything that maps onto an
 * M3 role (`primary`, `surface`, `onSurface`, `surfaceVariant`) belongs there
 * and **not** here; a second vocabulary for things Material already names is how
 * a design system becomes two design systems.
 *
 * **These are semantics, not colours.** A component asks for "the pet needs
 * attention", never "yellow". That is what makes the palette redesign a
 * one-file change instead of a search for literals — the problem this exists to
 * prevent, and which the care card had already introduced six of.
 *
 * **The same names exist on the pet**, as `#define`s in the firmware. The two
 * surfaces cannot share code — Compose against LVGL in C — but they must not
 * disagree, so they share the vocabulary and each holds its own value for it.
 * `PetStatusText` already does this for the *words*, and its precedence mirrors
 * the firmware's ranking for exactly the same reason.
 */
@Immutable
data class PetColors(
    /** The pet is well. Nothing is being asked of you. */
    val thriving: Color,
    /** Something needs doing: a score at zero, or sickness. */
    val needsAttention: Color,
    /** Death. Reserved for the one state that cannot be undone. */
    val critical: Color,
    /**
     * Not known yet — before the first report, or while the link is down.
     *
     * A first-class colour rather than an alpha on `onSurface`, because "we
     * have not been told" is a recurring state in this product with its own
     * rule: never render it as a default. It earns a name.
     */
    val unknown: Color,

    /**
     * The link to the pet, up and down.
     *
     * Deliberately NOT reusing [thriving] and [critical], even though they hold
     * the same values today. The link is infrastructure and the pet is the
     * point, so a designer may well want the link quieter than the pet's health
     * — and collapsing them now would make that a refactor rather than a
     * one-line change.
     */
    val linkUp: Color,
    val linkDown: Color,

    /**
     * The accent colour when it is carrying text rather than filling a shape.
     *
     * A meaning Material genuinely lacks, which is the bar §6.1 sets for adding
     * anything here. M3 has one `primary` and expects it to work as a fill *and*
     * as a foreground; this palette's gold is a fill that happens to be
     * unreadable as a foreground on cream — 1.9:1 where 4.5:1 is required. The
     * dark scheme has no such problem, so this is `PetGold` there and the two
     * schemes stay one vocabulary with different values, exactly as the rest of
     * this file works.
     *
     * Use it for a label, an icon or a border that sits ON a surface. Do not use
     * it for a fill: a gold button stays `primary`, because its text is
     * `onPrimary` and that pairing was never the problem.
     */
    val accentText: Color,

    /**
     * **The surface a filled bar is read against**, for the meters and the
     * usage bar.
     *
     * A meaning M3 lacks in the same way `accentText` is: Material's own answer
     * is that a progress track is a *parameter*, not a role, so there is nothing
     * to reach for. `surfaceVariant` and `outlineVariant` were each doing the
     * job on one of the two bars, which is how they came to differ.
     *
     * **It is dark in the light scheme, and that is the fix rather than a
     * mistake.** `primary` on the cream track measured **1.38:1** on the usage
     * bar and **1.72:1** on a meter, against the 3:1 a non-text indicator wants
     * — the gold fill and its track were very nearly the same brightness.
     *
     * **There is no light track that is both quiet and legible, and it is worth
     * knowing why.** Gold is itself a light colour, so a track lightening or
     * darkening *towards* it gets worse before it gets better: the ratio falls
     * from 1.38 through 1.01 before climbing again on the far side. The track
     * has to pass gold's luminance entirely. So an empty meter now reads as a
     * present dark segment rather than as an absence, which is a real change in
     * how a half-empty meter looks, taken deliberately — DESIGN.md §7.7 records
     * the alternative that was rejected.
     *
     * Light takes [LightOnSurfaceDeep], which was already in the palette rather
     * than invented here — the same discipline `accentText` followed — at
     * 3.74:1. Dark takes `outlineVariant`, which the usage bar already used and
     * which passes at 4.62:1.
     */
    val track: Color,
)

/**
 * Magenta on purpose. A missing [DigitalPetTheme] should be unmissable rather
 * than subtly wrong — the same instinct as the firmware logging loudly when a
 * capability is claimed but absent.
 */
val LocalPetColors = staticCompositionLocalOf {
    PetColors(
        thriving = Color.Magenta,
        needsAttention = Color.Magenta,
        critical = Color.Magenta,
        unknown = Color.Magenta,
        linkUp = Color.Magenta,
        linkDown = Color.Magenta,
        accentText = Color.Magenta,
        track = Color.Magenta,
    )
}

/** The current values. Provisional — see DESIGN.md §4; the palette is being redesigned. */
/*
 * LIGHT, added 2026-08-06 with the Claude Design palette.
 *
 * Not a tinted copy of the dark set. Two of the seven genuinely change value
 * rather than lightness: `thriving` uses the design's muted green, which would
 * read as almost black on #1A1A24, and `critical` warms so it sits in the same
 * family as the creams instead of shouting against them. Both were darkened a
 * step on 2026-08-09 to clear WCAG; the hexes live in Color.kt, which is the one
 * place they should be written down.
 *
 * `unknown` stays the hardest one and the rule is unchanged (§6.2): it means
 * "we have not been told", it appears whenever the link is down, and it must
 * NOT read as a value. Low-alpha on-surface keeps it looking absent rather than
 * like a reading of zero.
 */
val LightPetColors = PetColors(
    thriving = LightGreen,
    needsAttention = PetGold,
    critical = LightCritical,
    unknown = LightOnSurface.copy(alpha = 0.38f),
    linkUp = LightGreen,
    linkDown = LightCritical,
    // The gold that can be read. See LightGoldText.
    accentText = LightGoldText,
    // Dark, because a gold fill cannot be read against a cream one. See `track`.
    track = LightOnSurfaceDeep,
)

val DarkPetColors = PetColors(
    thriving = DarkGreen,
    needsAttention = WarningYellow,
    critical = ErrorRed,
    unknown = OnDarkSurface.copy(alpha = 0.45f),
    linkUp = DarkGreen,
    linkDown = ErrorRed,
    // Dark needs no darker gold: PetGold on #1A1A24 is comfortably legible, so
    // the two schemes share the vocabulary and differ in the value.
    accentText = PetGold,
    // Dark never had the problem: gold on this is 4.62:1. It is `outlineVariant`,
    // which the usage bar already used — the meter is what moved to join it.
    track = M3OutlineVariant,
)

/** Accessor, used as `PetTheme.colors.sick` beside `MaterialTheme.colorScheme`. */
object PetTheme {
    val colors: PetColors
        @Composable
        @ReadOnlyComposable
        get() = LocalPetColors.current
}
