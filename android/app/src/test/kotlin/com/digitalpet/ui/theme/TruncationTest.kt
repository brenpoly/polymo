package com.digitalpet.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A `Text` that can be squeezed must say what happens when it is.
 *
 * **Written after a chip spent a week reading `"Connected"` as `"Connect"`.**
 * `StatusChip` gave its label `Modifier.weight(1f, fill = false)` *and* put a
 * `Spacer(Modifier.weight(1f))` after it, so Compose split the free space 50/50
 * and the label got half a chip — about 38dp, which "Connected" does not fit in.
 *
 * The truncation is not the interesting part. **`Text`'s default overflow is
 * `Clip`**, so it was cut mid-word with no ellipsis, and a clean cut at a letter
 * boundary does not look like a layout problem — it looks like the wrong string.
 * It was hunted in `PetStatusText` first, where the string was correct all along.
 *
 * ### The rule
 *
 * If a `Text` constrains its lines (`maxLines`) *and* can be given less room
 * than it wants (`Modifier.weight`), it must declare an `overflow`. Declaring
 * `TextOverflow.Clip` explicitly passes: the point is that somebody decided,
 * not which way they decided.
 *
 * A `Text` with neither can still wrap, and one with `maxLines` but no weight
 * sizes to its content — neither can be silently cut, so neither is covered.
 */
class TruncationTest {

    private fun uiSourceRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val candidate = File(dir, "src/main/kotlin/com/digitalpet/ui")
            if (candidate.isDirectory) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError(
            "Cannot find the UI sources from ${File("").absolutePath} — this test " +
                "scans source, so a wrong working directory would make it pass by " +
                "finding nothing."
        )
    }

    /**
     * Every `Text(...)` call in a file, as source text.
     *
     * Brace-and-paren counting rather than a regex, because these calls run to
     * a dozen lines with nested lambdas and modifier chains in them. It also
     * skips string literals so that a bracket inside a message cannot unbalance
     * the scan.
     */
    internal fun textCalls(source: String): List<String> {
        val out = mutableListOf<String>()
        val marker = Regex("""(^|[^\w.])Text\(""")
        var searchFrom = 0
        while (true) {
            val m = marker.find(source, searchFrom) ?: break
            var i = m.range.last          // the '(' itself
            var depth = 0
            var inString = false
            val start = i
            while (i < source.length) {
                val c = source[i]
                when {
                    c == '"' && (i == 0 || source[i - 1] != '\\') -> inString = !inString
                    inString -> {}
                    c == '(' -> depth++
                    c == ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            out += source.substring(start, minOf(i + 1, source.length))
            searchFrom = i + 1
        }
        return out
    }

    // ---- the instrument, before it is believed -----------------------------

    @Test
    fun `the scanner finds a whole multi-line Text call`() {
        /*
         * **The literal here carries an UNBALANCED bracket, and that is the
         * whole test.** A first version used `"hello (world)"`, which is
         * balanced — so disabling the string-skipping entirely still parsed the
         * call correctly and the mutation passed. A balanced bracket cannot
         * prove a scanner ignores strings; only a lone one can.
         *
         * With the skipping off, the `)` closes the call at the literal, the
         * scanner never reaches `maxLines` or `weight`, and the rule below finds
         * nothing to complain about — a silent false pass, which is the failure
         * mode this whole file exists to prevent in someone else's code.
         */
        val fake = """
            fun x() {
                Text(
                    text = "not a smile :-)",
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(4.dp)
                )
            }
        """.trimIndent()
        val calls = textCalls(fake)
        assertEquals(1, calls.size)
        assertTrue("call did not reach maxLines: ${calls[0]}", calls[0].contains("maxLines"))
        assertTrue("call did not reach the modifier: ${calls[0]}", calls[0].contains("weight(1f)"))
    }

    @Test
    fun `the scanner does not mistake other calls for Text`() {
        // `BasicTextField(` and `annotatedText(` both contain "Text(" as a
        // substring; neither is the composable this rule is about.
        val fake = """
            BasicTextField(value = v, onValueChange = {})
            myText(1)
        """.trimIndent()
        assertEquals(emptyList<String>(), textCalls(fake))
    }

    // ---- the rule -----------------------------------------------------------

    @Test
    fun `a Text that is weighted and line-limited declares its overflow`() {
        val offences = uiSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                textCalls(file.readText())
                    .filter { it.contains("maxLines") && it.contains("weight(") }
                    .filter { !it.contains("overflow") }
                    .map { file.name }
            }
            .toList()

        assertTrue(
            buildString {
                append("${offences.size} Text(...) can be squeezed but does not say what ")
                append("happens when it is — add `overflow = TextOverflow.Ellipsis`, or ")
                append("`Clip` if a hard cut is genuinely wanted.\n")
                offences.distinct().forEach { append("  ").append(it).append('\n') }
            },
            offences.isEmpty(),
        )
    }
}
