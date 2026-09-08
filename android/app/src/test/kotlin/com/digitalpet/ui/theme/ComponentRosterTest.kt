package com.digitalpet.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ui/components/` against `design-system/components.txt`, by name.
 *
 * **The cheap half of component sync, and worth having precisely because the
 * expensive half is impossible.** A `.jsx` and a `.kt` cannot be diffed, so
 * nothing here says the two implementations agree. What it does say is that the
 * two *rosters* agree — and the likeliest structural drift by far is a component
 * that exists on one side only: designed and never built, built and never
 * designed, or renamed once.
 *
 * That was the state before 2026-08-09, when 16 of 19 components were `private
 * fun`s inside screen files and nothing anywhere could have told you.
 *
 * **The numbers inside the components are already covered**, since both sides
 * now take them from tokens and `TokenSyncTest` checks those. So this closes the
 * last mechanical gap; what is left needs eyes, which is §7.7's item 4.
 */
class ComponentRosterTest {

    private data class Entry(val path: String, val kind: String, val reason: String)

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            if (File(dir, "design-system/components.txt").isFile) return dir!!
            dir = dir?.parentFile
        }
        throw AssertionError(
            "design-system/components.txt not found from ${File("").absolutePath} — " +
                "this test reads it, so a wrong working directory would make it pass by " +
                "finding nothing to check."
        )
    }

    private fun roster(): List<Entry> =
        File(repoRoot(), "design-system/components.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split(Regex("\\s+"), limit = 3)
                Entry(parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
            }

    private fun codeComponents(): Set<String> {
        val root = File(repoRoot(), "android/app/src/main/kotlin/com/digitalpet/ui/components")
        assertTrue("ui/components/ does not exist at ${root.path}", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { "${it.parentFile!!.name}/${it.nameWithoutExtension}" }
            .toSet()
    }

    @Test
    fun `the roster file is readable and not empty`() {
        // Prove the instrument before believing its silence: an unparsed roster
        // would make every comparison below trivially true.
        val entries = roster()
        assertTrue("roster parsed ${entries.size} entries, expected at least 19", entries.size >= 19)
        assertTrue(
            "roster has entries with no kind",
            entries.all { it.kind in setOf("both", "design-only", "code-only") },
        )
    }

    @Test
    fun `every exception in the roster carries a reason`() {
        // The rule that keeps the file honest. An unexplained asymmetry is
        // indistinguishable from an oversight, and a roster nobody trusts is a
        // roster nobody reads.
        val silent = roster().filter { it.kind != "both" && it.reason.isBlank() }
        assertTrue(
            "these exceptions state no reason:\n" + silent.joinToString("\n") { "  ${it.path} (${it.kind})" },
            silent.isEmpty(),
        )
    }

    @Test
    fun `the built components are exactly the ones the roster expects`() {
        val expected = roster().filter { it.kind != "design-only" }.map { it.path }.toSet()
        val actual = codeComponents()

        val unbuilt = expected - actual
        val unlisted = actual - expected

        assertTrue(
            buildString {
                if (unbuilt.isNotEmpty()) {
                    append("The roster expects components that ui/components/ does not have:\n")
                    unbuilt.sorted().forEach { append("  $it\n") }
                }
                if (unlisted.isNotEmpty()) {
                    append("ui/components/ has components the roster does not mention:\n")
                    unlisted.sorted().forEach { append("  $it\n") }
                    append("  → add it to design-system/components.txt. If the design system\n")
                    append("    has no card for it, mark it code-only WITH A REASON.\n")
                }
            },
            unbuilt.isEmpty() && unlisted.isEmpty(),
        )
    }

    @Test
    fun `twenty components, which is what the design system says there are`() {
        /*
         * A count, stated separately, because the set comparison above would
         * stay green if a name were changed on both sides at once — and the
         * total is the number DESIGN.md and DESIGN-SYSTEM.md both publish.
         *
         * It counts what the APP has, so `design-only` entries are excluded:
         * `core/Icon` is a web-preview wrapper the app gets from Material, and
         * counting it would make this 20 and the published number wrong.
         */
        /*
         * NINETEEN UNTIL 2026-08-27, when core/PetButton was added — the count
         * moving is the point of this test rather than a nuisance. It forced the
         * published number in DESIGN.md to move with it, which is exactly the
         * drift it exists to catch.
         */
        val ours = roster().count { it.kind != "design-only" }
        assertTrue("roster has $ours built components, the design system says 20", ours == 20)
    }
}
