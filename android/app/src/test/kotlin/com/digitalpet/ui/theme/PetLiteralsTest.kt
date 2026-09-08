package com.digitalpet.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No UI file may write a raw `.dp` or `.sp`. They come from [PetSpacing],
 * [PetRadius], [PetSize] and [PetTextSize], or they do not exist.
 *
 * **This is the test that makes the token file stick rather than decay**, and it
 * is DESIGN.md §7.7's second item for that reason: a token file nothing enforces
 * is a suggestion, and the 277 literals it replaced accumulated one reasonable
 * call site at a time. Nothing about any of them looked wrong.
 *
 * ### Why a source scan and not a lint rule
 *
 * A custom lint check would be the tidier answer and needs a dependency this
 * project cannot fetch — it builds `--offline`. This needs nothing: the sources
 * are on disk at test time, and reading them is the same trick `ContrastTest`
 * plays with the palette. Both are the same idea, which DESIGN.md §7.7 puts
 * plainly: **the visual properties that are arithmetic never needed eyes, and
 * leaving them to a reviewer was the mistake.**
 *
 * ### What it does not cover, and why that is not a gap being hidden
 *
 * It cannot tell a *well-chosen* token from a wrong one. `PetSpacing.s24` where
 * the design says 16 passes here and is still wrong. What it removes is the
 * class of drift where a value has no name at all — the one where nothing could
 * tell an 8 that should be a token from a 9 that was a typo, and nothing stopped
 * one value being applied twice. Colour and type still want eyes; §7.7 item 4.
 */
class PetLiteralsTest {

    /**
     * `0.dp` is a **bound, not a value.** It appears as the floor of a
     * `coerceAtLeast` where the arithmetic can go negative, and there is no
     * design decision in it — a token called `PetSpacing.zero` would be a name
     * for nothing. Adding a rung to the scale to express "no smaller than
     * nothing" would make the scale less honest, not more.
     */
    private val allowed = setOf("0.dp", "0.sp")

    /**
     * Files that are *allowed* to hold raw units, because they are where the
     * units are defined.
     *
     * - `PetTokens.kt` is the token file itself.
     * - `Type.kt` is the type scale — Material's own sizes and line heights,
     *   15 styles. [PetTextSize] holds the *overrides* screens apply on top of
     *   it, which is a different thing and a debt in its own right; see the note
     *   there. Folding the two together is a typography decision, not a
     *   refactor, so this exemption is deliberate and narrow.
     */
    private val definitionFiles = setOf("PetTokens.kt", "Type.kt")

    private val unitPattern = Regex("""\b\d+(?:\.\d+)?\.(?:dp|sp)\b""")

    // ---- finding the sources ----------------------------------------------

    /**
     * The UI source tree, found by walking up from wherever Gradle put us.
     *
     * **It throws rather than returning empty, and that is the whole point.** A
     * scanner that cannot find its input reports no violations, which is
     * indistinguishable from a clean tree — the exact failure mode DESIGN.md's
     * "prove your instrument is alive before believing its silence" was written
     * about. Every other test in this file depends on this one not lying.
     */
    private fun uiSourceRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val candidate = File(dir, "src/main/kotlin/com/digitalpet/ui")
            if (candidate.isDirectory) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError(
            "Cannot find src/main/kotlin/com/digitalpet/ui from ${File("").absolutePath} — " +
                "this test scans source, so a wrong working directory would make it " +
                "pass by finding nothing."
        )
    }

    private fun uiSources(): List<File> =
        uiSourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ---- reading them without being fooled by their own prose --------------

    /**
     * Source with comments and string literals blanked out.
     *
     * **Necessary, not defensive.** These files document the numbers they used
     * to contain — `Meter` explains that it drew `RoundedCornerShape(5.dp)` on a
     * 9dp box and why saying `pill` is the same pixels — so a scanner that read
     * comments would report a violation in the very sentence explaining the fix,
     * and the only way to make it pass would be to delete the explanation. A
     * test that punishes writing things down is worse than no test.
     *
     * Replaces rather than deletes, so byte offsets and line numbers survive and
     * a failure can name the line.
     */
    internal fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val rest = source.length - i
            when {
                rest >= 2 && source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') { out.append(' '); i++ }
                }
                rest >= 2 && source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2).let {
                        if (it < 0) source.length else it + 2
                    }
                    while (i < end) { out.append(if (source[i] == '\n') '\n' else ' '); i++ }
                }
                rest >= 3 && source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let {
                        if (it < 0) source.length else it + 3
                    }
                    while (i < end) { out.append(if (source[i] == '\n') '\n' else ' '); i++ }
                }
                source[i] == '"' -> {
                    out.append(' '); i++
                    while (i < source.length && source[i] != '"' && source[i] != '\n') {
                        if (source[i] == '\\' && i + 1 < source.length) { out.append(' '); i++ }
                        out.append(' '); i++
                    }
                    if (i < source.length && source[i] == '"') { out.append(' '); i++ }
                }
                else -> { out.append(source[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun violationsIn(source: String): List<Pair<Int, String>> =
        stripCommentsAndStrings(source).lines().flatMapIndexed { index, line ->
            unitPattern.findAll(line)
                .map { it.value }
                .filter { it !in allowed }
                .map { (index + 1) to it }
                .toList()
        }

    // ---- the instrument, checked before it is believed ---------------------

    @Test
    fun `the scanner finds a literal that is really there`() {
        val fake = """
            package com.digitalpet.ui.screens
            fun x() { Modifier.padding(16.dp) }
        """.trimIndent()
        assertEquals(listOf(2 to "16.dp"), violationsIn(fake))
    }

    @Test
    fun `the scanner ignores a literal inside a comment or a string`() {
        val fake = """
            package com.digitalpet.ui.screens
            // this used to be 12.dp
            /* and before that 40.dp */
            val note = "the design says 18.dp"
            fun x() { Modifier.padding(PetSpacing.s16) }
        """.trimIndent()
        assertEquals(emptyList<Pair<Int, String>>(), violationsIn(fake))
    }

    @Test
    fun `the scanner ignores a token reference and a bare number`() {
        // PetSpacing.s16 must not read as "16" followed by ".dp", and a plain
        // integer in an animation keyframe is not a unit.
        val fake = """
            fun x() {
                Modifier.padding(PetSpacing.s16).size(PetSize.icon20)
                val frames = 5400
                translationY = -value * 12f
            }
        """.trimIndent()
        assertEquals(emptyList<Pair<Int, String>>(), violationsIn(fake))
    }

    @Test
    fun `the scan reaches the whole UI tree`() {
        /*
         * The count is a floor, not an assertion about the tree's shape — adding
         * a screen must not fail this. What it catches is the failure that would
         * otherwise be silent: a scan that found two files and declared victory.
         * There are 19 components, 4 screens, 3 nav files and 6 theme files
         * today, so 25 is comfortably under and still nowhere near zero.
         */
        val files = uiSources()
        assertTrue(
            "Only ${files.size} UI source files found — the scan is not reaching the tree",
            files.size >= 25,
        )
        assertTrue(
            "PetHomeScreen.kt is not among the scanned files",
            files.any { it.name == "PetHomeScreen.kt" },
        )
    }

    // ---- the rule -----------------------------------------------------------

    @Test
    fun `no UI file writes a raw dp or sp`() {
        val offences = uiSources()
            .filter { it.name !in definitionFiles }
            .flatMap { file ->
                violationsIn(file.readText()).map { (line, text) ->
                    "${file.name}:$line  $text"
                }
            }

        assertTrue(
            buildString {
                append("${offences.size} raw unit literal(s) in UI code. ")
                append("Use PetSpacing / PetRadius / PetSize / PetTextSize — ")
                append("and if the value is not on those scales, add it there, ")
                append("where the addition is visible in a diff.\n")
                offences.forEach { append("  ").append(it).append('\n') }
            },
            offences.isEmpty(),
        )
    }

    /**
     * The token file is the one place raw units live, and it must keep at least
     * one — otherwise the exemption above is protecting nothing and the previous
     * test is passing for the wrong reason.
     */
    @Test
    fun `the token file is where the raw units actually are`() {
        val tokens = uiSources().single { it.name == "PetTokens.kt" }
        assertTrue(
            "PetTokens.kt defines no raw units, so it is no longer the token file",
            violationsIn(tokens.readText()).size >= 30,
        )
    }
}
