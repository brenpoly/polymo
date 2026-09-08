package com.digitalpet.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The code against the vendored `design-system/tokens/` CSS, value by value.
 *
 * (That path is written without a glob on purpose: Kotlin block comments NEST,
 * so a `/` immediately followed by a `*` inside one opens a second comment that
 * never closes, and the whole file after it becomes parse errors. It reads as an
 * unclosed comment three hundred lines further down.)
 *
 * **This is the gate on the round trip, and it exists because the round trip
 * broke within an hour of being made mandatory.** DESIGN.md §7.2 makes the
 * Claude Design system the source of truth; an authority that lags the code
 * stops deserving that, and nothing but a person remembering was keeping the two
 * together. Now a divergence fails the build in whichever direction it came
 * from.
 *
 * ### It reads a vendored copy, and that is deliberate
 *
 * `design-system/tokens/` is a checked-in copy of the design project's token
 * files, not the project itself. Three reasons, in order of how much they
 * matter:
 *
 * 1. **The build is `--offline`.** A test that made a network call would fail
 *    for the wrong reason on a train.
 * 2. **A diff needs a base.** Comparing against a live remote tells you the
 *    values differ; comparing against a pinned copy tells you *someone changed
 *    something*, which is the useful sentence.
 * 3. **It puts the pull in the workflow rather than in the test.** Refreshing
 *    the copy is a deliberate act with a commit attached, so "the design moved"
 *    is visible in history instead of arriving silently one morning.
 *
 * `tools/design-sync.sh` is the front door; it explains both directions.
 *
 * ### What it cannot do
 *
 * It compares *values*, so it cannot tell a well-chosen token from a wrong one,
 * and it says nothing about type rendering or composition. Those are the
 * screenshot-test gap in §7.7 — still open, and this does not narrow it.
 */
class TokenSyncTest {

    // ---- finding and parsing the vendored tokens ---------------------------

    /**
     * **Throws rather than skipping.** The same rule as `PetLiteralsTest`: a
     * checker that cannot find its input reports nothing wrong, which reads
     * exactly like agreement. Every assertion below is worthless if this lies.
     */
    private fun tokenFile(name: String): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val candidate = File(dir, "design-system/tokens/$name")
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError(
            "design-system/tokens/$name not found from ${File("").absolutePath}. " +
                "It is a vendored copy of the Claude Design project — see tools/design-sync.sh."
        )
    }

    /** `--name: value;` pairs, with one level of `var(--x)` resolved. */
    private fun vars(name: String): Map<String, String> {
        val text = tokenFile(name).readText()
        val raw = Regex("""--([a-z0-9-]+)\s*:\s*([^;]+);""")
            .findAll(stripComments(text))
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        return raw.mapValues { (_, v) ->
            Regex("""var\(--([a-z0-9-]+)\)""").replace(v) { m -> raw[m.groupValues[1]] ?: m.value }
        }
    }

    /**
     * CSS comments out, so the prose in the token files cannot be parsed as
     * values — those files carry a lot of it, and most of it quotes hexes.
     *
     * The pattern uses character classes rather than escaping the comment
     * delimiters directly, so that neither delimiter appears literally in this
     * source. See the note at the top of the file about nesting.
     */
    private fun stripComments(css: String) =
        css.replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), " ")

    /**
     * `colors.css` states DARK at `:root` and overrides LIGHT under
     * `[data-theme="light"]`, which is one file holding two schemes — so it has
     * to be split before it is parsed.
     *
     * **The first version of this test did not split it**, parsed the whole file
     * into one map, and `associate` kept whichever came last. Every dark role
     * was therefore compared against the light value, and thirty-three of them
     * "failed". The test caught its own bug on its first run, which is the
     * argument for the instrument check below being a real test rather than
     * ceremony.
     *
     * Light also *inherits*: a role the light block does not restate keeps the
     * `:root` value, `surfaceTint` being the live example. So light is root
     * overlaid with light, never light alone.
     */
    private fun schemeBlocks(): Pair<String, String> {
        val text = stripComments(tokenFile("colors.css").readText())
        val i = text.indexOf("[data-theme=\"light\"]")
        assertTrue("colors.css has no [data-theme=\"light\"] block", i > 0)
        return text.substring(0, i) to text.substring(i)
    }

    private fun declarations(block: String): Map<String, String> =
        Regex("""--([a-z0-9-]+)\s*:\s*([^;]+);""")
            .findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun resolve(raw: Map<String, String>, fallback: Map<String, String> = emptyMap()) =
        raw.mapValues { (_, v) ->
            Regex("""var\(--([a-z0-9-]+)\)""").replace(v) { m ->
                raw[m.groupValues[1]] ?: fallback[m.groupValues[1]] ?: m.value
            }
        }

    /** The dark scheme: the `:root` block alone. */
    private fun darkVars(): Map<String, String> = resolve(declarations(schemeBlocks().first))

    /** The light scheme: `:root` overlaid with the light block. */
    private fun lightVars(): Map<String, String> {
        val root = darkVars()
        return root + resolve(declarations(schemeBlocks().second), root)
    }

    private fun hex(c: Color): String =
        "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    private fun px(v: String): Int = v.removeSuffix("px").trim().toInt()

    // ---- the instrument, checked before it is believed ---------------------

    @Test
    fun `the parser reads values, resolves var() and ignores comments`() {
        val spacing = vars("spacing.css")
        assertTrue("no vars parsed from spacing.css", spacing.size >= 20)
        assertTrue("--space-18 missing", spacing["space-18"] == "18px")

        // --primary is `var(--pet-gold)` in the file; unresolved it would be
        // compared as the literal string "var(--pet-gold)" and every colour
        // assertion below would fail for the wrong reason.
        assertTrue("var() was not resolved: ${darkVars()["primary"]}", darkVars()["primary"] == "#F5A623")

        // THE SPLIT, and it is the assertion that matters most here: one file
        // holds both schemes, and reading it as one map silently gives you the
        // light values twice.
        assertTrue("dark on-surface-variant wrong: ${darkVars()["on-surface-variant"]}",
            darkVars()["on-surface-variant"] == "#E8E6F0")
        assertTrue("light on-surface-variant wrong: ${lightVars()["on-surface-variant"]}",
            lightVars()["on-surface-variant"] == "#736A56")

        // Light INHERITS what it does not restate. surfaceTint is only in :root.
        assertTrue("light did not inherit surface-tint from :root",
            lightVars()["surface-tint"] == "#F5A623")
    }

    // ---- colour ------------------------------------------------------------

    /** CSS custom property → the `ColorScheme` role it is the same thing as. */
    private val colourRoles: List<Pair<String, (androidx.compose.material3.ColorScheme) -> Color>> = listOf(
        "background" to { s -> s.background },
        "surface" to { s -> s.surface },
        "surface-variant" to { s -> s.surfaceVariant },
        "surface-container-lowest" to { s -> s.surfaceContainerLowest },
        "surface-container-low" to { s -> s.surfaceContainerLow },
        "surface-container" to { s -> s.surfaceContainer },
        "surface-container-high" to { s -> s.surfaceContainerHigh },
        "surface-container-highest" to { s -> s.surfaceContainerHighest },
        "surface-dim" to { s -> s.surfaceDim },
        "surface-bright" to { s -> s.surfaceBright },
        "primary" to { s -> s.primary },
        "on-primary" to { s -> s.onPrimary },
        "primary-container" to { s -> s.primaryContainer },
        "on-primary-container" to { s -> s.onPrimaryContainer },
        "inverse-primary" to { s -> s.inversePrimary },
        "secondary" to { s -> s.secondary },
        "on-secondary" to { s -> s.onSecondary },
        "secondary-container" to { s -> s.secondaryContainer },
        "on-secondary-container" to { s -> s.onSecondaryContainer },
        "tertiary" to { s -> s.tertiary },
        "on-tertiary" to { s -> s.onTertiary },
        "tertiary-container" to { s -> s.tertiaryContainer },
        "on-tertiary-container" to { s -> s.onTertiaryContainer },
        "on-background" to { s -> s.onBackground },
        "on-surface" to { s -> s.onSurface },
        "on-surface-variant" to { s -> s.onSurfaceVariant },
        "outline" to { s -> s.outline },
        "outline-variant" to { s -> s.outlineVariant },
        "scrim" to { s -> s.scrim },
        "inverse-surface" to { s -> s.inverseSurface },
        "inverse-on-surface" to { s -> s.inverseOnSurface },
        "surface-tint" to { s -> s.surfaceTint },
        "error" to { s -> s.error },
        "on-error" to { s -> s.onError },
        "error-container" to { s -> s.errorContainer },
        "on-error-container" to { s -> s.onErrorContainer },
    )

    private fun checkScheme(
        label: String,
        css: Map<String, String>,
        scheme: androidx.compose.material3.ColorScheme,
    ) {
        val wrong = colourRoles.mapNotNull { (name, read) ->
            val expected = css[name] ?: return@mapNotNull "$name is absent from colors.css"
            val actual = hex(read(scheme))
            if (!expected.equals(actual, ignoreCase = true))
                "$name: design says $expected, $label scheme has $actual" else null
        }
        assertTrue(
            "${wrong.size} colour role(s) out of sync with design-system/tokens/colors.css.\n" +
                "  The design system is the source of truth (DESIGN.md §7.2): change the\n" +
                "  code, unless the CSS is a stale snapshot — then push the code back.\n" +
                wrong.joinToString("\n") { "  $it" },
            wrong.isEmpty(),
        )
    }

    @Test
    fun `every dark colour role matches the design system`() =
        checkScheme("dark", darkVars(), DarkColorScheme)

    @Test
    fun `every light colour role matches the design system`() =
        checkScheme("light", lightVars(), LightColorScheme)

    @Test
    fun `PetColors matches the design system in both schemes`() {
        /*
         * `unknown` is deliberately absent. It is `rgba(…)` in CSS and
         * `Color.copy(alpha = …)` in Kotlin, and comparing composited floats
         * against a decimal string would fail on representation rather than on
         * meaning. The value it is an alpha OF is checked as `on-surface` above,
         * and the alpha itself is a semantic the design system states in prose.
         */
        val pairs = listOf<Triple<String, (PetColors) -> Color, String>>(
            Triple("thriving", { p -> p.thriving }, "thriving"),
            Triple("needs-attention", { p -> p.needsAttention }, "needsAttention"),
            Triple("critical", { p -> p.critical }, "critical"),
            Triple("link-up", { p -> p.linkUp }, "linkUp"),
            Triple("link-down", { p -> p.linkDown }, "linkDown"),
            Triple("accent-text", { p -> p.accentText }, "accentText"),
            Triple("track", { p -> p.track }, "track"),
        )
        val wrong = listOf("dark" to (darkVars() to DarkPetColors),
                           "light" to (lightVars() to LightPetColors))
            .flatMap { (label, it) ->
                val (css, pet) = it
                pairs.mapNotNull { (cssName, read, ktName) ->
                    val expected = css[cssName] ?: return@mapNotNull "$cssName absent from colors.css"
                    val actual = hex(read(pet))
                    if (!expected.equals(actual, ignoreCase = true))
                        "$label $ktName: design says $expected, code has $actual" else null
                }
            }
        assertTrue("PetColors out of sync:\n" + wrong.joinToString("\n") { "  $it" }, wrong.isEmpty())
    }

    // ---- spacing, shape, geometry ------------------------------------------

    @Test
    fun `the spacing ladder holds every rung the design system defines`() {
        /*
         * A SET comparison, not a name mapping, and that is the right shape:
         * `--space-14` and `PetSpacing.s14` are the same claim made twice, so
         * checking the *values* catches a rung added on one side without
         * inventing a naming contract between CSS and Kotlin that neither file
         * agreed to.
         */
        val design = vars("spacing.css")
            .filterKeys { it.startsWith("space-") }
            .values.map { px(it) }.toSet()
        val code = setOf(
            PetSpacing.s2, PetSpacing.s3, PetSpacing.s4, PetSpacing.s5, PetSpacing.s6,
            PetSpacing.s7, PetSpacing.s8, PetSpacing.s10, PetSpacing.s11, PetSpacing.s12,
            PetSpacing.s13, PetSpacing.s14, PetSpacing.s16, PetSpacing.s18, PetSpacing.s20,
            PetSpacing.s24, PetSpacing.s32,
        ).map { it.value.toInt() }.toSet()

        val missing = design - code
        assertTrue(
            "the design system defines spacing the code has no rung for: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the screen margin is the same number on both sides`() {
        // Named separately from the ladder because it is the one value with a
        // meaning attached, and the one most likely to be changed on purpose.
        assertTrue(
            "screen margin: design says ${vars("spacing.css")["space-18"]}, code has ${PetSpacing.screenMargin}",
            px(vars("spacing.css")["space-18"]!!) == PetSpacing.screenMargin.value.toInt(),
        )
    }

    @Test
    fun `every radius the design system defines exists in PetRadius`() {
        val design = vars("shape.css").values
            .filter { it.endsWith("px") }
            .map { px(it) }
            .filter { it < 999 }   // --radius-pill is a sentinel, not a length
            .toSet()
        val code = setOf(
            PetRadius.r4, PetRadius.r6, PetRadius.r7, PetRadius.r8, PetRadius.r11,
            PetRadius.r12, PetRadius.r14, PetRadius.r16, PetRadius.r18, PetRadius.r20,
            PetRadius.r24, PetRadius.r30, PetRadius.r34, PetRadius.r40,
        ).map { it.value.toInt() }.toSet()

        val missing = design - code
        assertTrue("the design system defines radii the code does not have: $missing", missing.isEmpty())
    }

    @Test
    fun `LOAD-BEARING GEOMETRY is identical on both sides`() {
        /*
         * The one group where a name mapping is worth writing out, because
         * `spacing.css` says of these that moving them changes behaviour rather
         * than appearance. A mismatch here is a bug, not a style drift.
         */
        val design = vars("spacing.css")
        val named = listOf(
            "touch-min" to PetSize.touchMin,
            "sheet-peek" to PetSize.sheetPeek,
            "sheet-content" to PetSize.sheetContent,
            "pet-panel-width" to PetSize.petPanelWidth,
            "pet-panel-height" to PetSize.petPanelHeight,
            "chip-height" to PetSize.chipHeight,
            "meter-segment-height" to PetSize.meterSegmentHeight,
            "usage-bar-height" to PetSize.usageBarHeight,
        )
        val wrong = named.mapNotNull { (name, value) ->
            val expected = design[name] ?: return@mapNotNull "$name absent from spacing.css"
            if (px(expected) != value.value.toInt())
                "$name: design says $expected, code has $value" else null
        }
        assertTrue("load-bearing geometry out of sync:\n" + wrong.joinToString("\n") { "  $it" }, wrong.isEmpty())
    }

    // ---- type ---------------------------------------------------------------

    @Test
    fun `every type style matches the design system in size, line height and weight`() {
        /*
         * `--label-medium: 600 12px/16px var(--font-sans)` — weight, size, line
         * height, family. The family is not compared: the design system loads the
         * faces from a CDN because the Compose artifact ships no web-usable
         * binaries, and DESIGN-SYSTEM.md calls that a delivery substitution
         * rather than a design one.
         *
         * THIS FOUND A CONTRADICTION ON ITS FIRST RUN. `--headline-large` read
         * `500`, which is Medium, while its own comment said SemiBold and
         * DESIGN-SYSTEM.md §3 said SemiBold — and Type.kt has had SemiBold all
         * along. The design system disagreed with itself in the machine-readable
         * half; the prose and the code agreed. Corrected in the CSS.
         */
        val css = vars("typography.css")
        val shorthand = Regex("""(\d+)\s+(\d+)px/(\d+)px""")
        val styles = listOf<Triple<String, TextStyle, String>>(
            Triple("display-large", Typography.displayLarge, "displayLarge"),
            Triple("display-medium", Typography.displayMedium, "displayMedium"),
            Triple("display-small", Typography.displaySmall, "displaySmall"),
            Triple("headline-large", Typography.headlineLarge, "headlineLarge"),
            Triple("headline-medium", Typography.headlineMedium, "headlineMedium"),
            Triple("headline-small", Typography.headlineSmall, "headlineSmall"),
            Triple("title-large", Typography.titleLarge, "titleLarge"),
            Triple("title-medium", Typography.titleMedium, "titleMedium"),
            Triple("title-small", Typography.titleSmall, "titleSmall"),
            Triple("body-large", Typography.bodyLarge, "bodyLarge"),
            Triple("body-medium", Typography.bodyMedium, "bodyMedium"),
            Triple("body-small", Typography.bodySmall, "bodySmall"),
            Triple("label-large", Typography.labelLarge, "labelLarge"),
            Triple("label-medium", Typography.labelMedium, "labelMedium"),
            Triple("label-small", Typography.labelSmall, "labelSmall"),
        )
        val wrong = styles.flatMap { (cssName, style, ktName) ->
            val decl = css[cssName] ?: return@flatMap listOf("$cssName absent from typography.css")
            val m = shorthand.find(decl) ?: return@flatMap listOf("$cssName is not `weight NNpx/NNpx`: $decl")
            val (w, size, line) = m.destructured
            listOfNotNull(
                if (w.toInt() != style.fontWeight?.weight)
                    "$ktName weight: design says $w, code has ${style.fontWeight?.weight}" else null,
                if (size.toInt() != style.fontSize.value.toInt())
                    "$ktName size: design says ${size}px, code has ${style.fontSize}" else null,
                if (line.toInt() != style.lineHeight.value.toInt())
                    "$ktName line height: design says ${line}px, code has ${style.lineHeight}" else null,
            )
        }
        assertTrue(
            "${wrong.size} type mismatch(es) against design-system/tokens/typography.css:\n" +
                wrong.joinToString("\n") { "  $it" },
            wrong.isEmpty(),
        )
    }
}
