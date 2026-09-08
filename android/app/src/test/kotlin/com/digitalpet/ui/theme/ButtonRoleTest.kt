package com.digitalpet.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No screen may reach for a Material button directly.
 *
 * **The same instrument as `PetLiteralsTest`, for the same reason.** A raw `.dp`
 * is a number that escaped the tokens; a raw `Button(` is a *role* that escaped
 * the design. Both are invisible in review because the result looks completely
 * ordinary — a 40 dp button sits next to another 40 dp button and neither looks
 * wrong.
 *
 * **What it caught when it was written.** Twenty call sites, four different
 * answers between them: filled, outlined, text, and text with a hand-passed
 * colour. Nine of them re-specified `contentColor = accentText` individually and
 * one — the newest, on the Permissions page — did not, so it came out in
 * Material's `primary` and looked like a different kind of control. And
 * **eighteen of the twenty set no height at all**, leaving them at Material's
 * 40 dp minimum, 8 dp under the 48 dp `DESIGN.md` §7.5 lists as load-bearing.
 *
 * Buttons are checked rather than merely provided because providing was already
 * true of `Card` and `BadgePill`, and the buttons drifted anyway.
 */
class ButtonRoleTest {

    /** The one file allowed to name Material's buttons: the roles are built on them. */
    private val theRoleFile = "PetButton.kt"

    private val forbidden = listOf(
        Regex("""(?<![\w.])Button\s*\("""),
        Regex("""(?<![\w.])OutlinedButton\s*\("""),
        Regex("""(?<![\w.])TextButton\s*\("""),
        Regex("""(?<![\w.])ElevatedButton\s*\("""),
        Regex("""(?<![\w.])FilledTonalButton\s*\("""),
    )

    @Test
    fun `every button in the app goes through a role`() {
        val offenders = mutableListOf<String>()
        uiSources().forEach { file ->
            if (file.name == theRoleFile) return@forEach
            file.readLines().forEachIndexed { i, raw ->
                val line = raw.substringBefore("//")
                if (line.trimStart().startsWith("*")) return@forEachIndexed
                if (line.trimStart().startsWith("import ")) return@forEachIndexed
                // PetButton( and PetButtonSecondary( must not match Button( — the
                // lookbehind handles that, and this is the case worth stating.
                forbidden.forEach { rx ->
                    if (rx.containsMatchIn(line)) {
                        offenders += "${file.name}:${i + 1}  ${raw.trim()}"
                    }
                }
            }
        }
        assertTrue(
            "These reach for a Material button instead of a role. Use PetButton " +
                "(the action a screen is for), PetButtonSecondary (an alternative " +
                "or one of several peers), or PetTextButton (dialogs, skipping). " +
                "The roles carry the 48dp minimum DESIGN.md 7.5 calls load-bearing " +
                "and the accent colour, so a raw one is 8dp short and the wrong " +
                "colour without looking it:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the roles themselves state the touch minimum`() {
        // If this file stopped applying heightIn, every button in the app would
        // silently drop to Material's 40dp and the test above would still pass —
        // it only proves the calls go through here, not that here is right.
        val role = uiSources().first { it.name == theRoleFile }.readText()
        assertTrue(
            "PetButton.kt no longer applies the 48dp touch minimum",
            role.contains("heightIn(min = PetSize.touchMin)"),
        )
        assertTrue(
            "the three roles must each carry it",
            Regex("heightIn\\(min = PetSize\\.touchMin\\)").findAll(role).count() == 3,
        )
    }

    @Test
    fun `the instrument finds sources at all`() {
        // Prove it before believing its silence: a wrong working directory would
        // scan nothing and pass.
        assertTrue("scanned no UI sources", uiSources().count() > 20)
    }

    private fun uiSources(): Sequence<File> {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val candidate = File(dir, "android/app/src/main/kotlin/com/digitalpet/ui")
            if (candidate.isDirectory) return candidate.walkTopDown().filter { it.extension == "kt" }
            val direct = File(dir, "src/main/kotlin/com/digitalpet/ui")
            if (direct.isDirectory) return direct.walkTopDown().filter { it.extension == "kt" }
            dir = dir?.parentFile
        }
        throw AssertionError("Cannot find ui/ from ${File("").absolutePath}")
    }
}
