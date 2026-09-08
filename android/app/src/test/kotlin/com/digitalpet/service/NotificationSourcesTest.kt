package com.digitalpet.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **A `StateFlow` read with `.value` inside a collector is not observed.**
 *
 * `startNotificationUpdates` dedupes on what it RENDERS, and the face set is
 * rendered — so the set was correctly written into the dedupe key. It was read
 * with `petBle.faceSetId.value` from inside the `collect`, and combined with
 * nothing. The key could therefore never be *evaluated* on a set change:
 * changing personality moved the pet and the app together and left the shade
 * showing the old face until something unrelated moved the condition.
 *
 * Reported from a phone, on 2026-08-11, and invisible everywhere else. The
 * comment beside the key said the set was in it "so the shade would not keep
 * the old one", which was true of the key and false of the behaviour — the
 * exact shape of gotcha 2c and of the three `sleep_tick` bugs: **a check that
 * cannot fire is worse than no check, because the note beside it says the case
 * is handled.**
 *
 * ### Why this reads the source
 *
 * Like `PetLiteralsTest` and `TruncationTest`. The defect is not a wrong value,
 * it is a missing edge in a dataflow graph — nothing to assert about an output,
 * because with a static set the output is correct. What can be checked is the
 * shape: inside this function, state must arrive through the `combine` and not
 * through `.value`.
 *
 * This is deliberately scoped to one function. The rule is not "never call
 * `.value`" — `buildNotification` does, and correctly, because it is called
 * *after* something has already woken it.
 *
 * ### It found a second one on its first run
 *
 * `petBle.pairedAddress` was read the same way, for the subtitle. So the shade
 * could not update when a pet was paired or forgotten either — and those are
 * the two sentences `PetStatusText` keeps deliberately different, "Not
 * connected" for a pet that is remembered and "No pet paired yet" for one that
 * never was. One needs setting up; the other needs walking into the next room.
 * Nobody had reported it, and it is the same defect with a quieter symptom.
 */
class NotificationSourcesTest {

    private fun serviceSource(): String {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val f = File(dir, "app/src/main/kotlin/com/digitalpet/service/PetForegroundService.kt")
            if (f.isFile) return f.readText()
            dir = dir?.parentFile
        }
        throw AssertionError(
            "PetForegroundService.kt not found from ${File("").absolutePath} — this test " +
                "reads the file, so a wrong path would make it pass by finding nothing."
        )
    }

    /** The body of `startNotificationUpdates`, by brace balance from its signature. */
    private fun updaterBody(): String {
        val src = serviceSource()
        val start = src.indexOf("private fun startNotificationUpdates()")
        assertTrue("startNotificationUpdates() is gone — has it been renamed?", start >= 0)
        var i = src.indexOf('{', start)
        var depth = 0
        val open = i
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("could not find the end of startNotificationUpdates()")
    }

    @Test
    fun `the notification updater takes every input from the combine, never from value`() {
        val body = updaterBody()
        val reads = Regex("""\b(petBle|models)\.(\w+)\.value""")
            .findAll(body)
            .map { "${it.groupValues[1]}.${it.groupValues[2]}.value" }
            .toSet()

        assertTrue(
            buildString {
                append("These are read with `.value` inside startNotificationUpdates:\n")
                reads.sorted().forEach { append("  $it\n") }
                append("\n")
                append("A `.value` read inside a collector is a SNAPSHOT, not a subscription.\n")
                append("Whatever it is used for — the dedupe key or the notification itself —\n")
                append("nothing will wake this flow when it changes, so the shade will show a\n")
                append("stale value until some other input happens to move.\n")
                append("  → add the flow to the `combine` above and destructure it out.\n")
                append("That is exactly what went wrong with faceSetId: it was in the key and\n")
                append("the key could never be evaluated.\n")
            },
            reads.isEmpty(),
        )
    }

    @Test
    fun `the face set is one of the combined flows`() {
        val body = updaterBody()
        val combine = Regex("""combine\((.*?)\)\s*\{""", RegexOption.DOT_MATCHES_ALL)
            .find(body)
        assertTrue("no combine(...) in startNotificationUpdates", combine != null)
        val args = combine!!.groupValues[1]

        // Named individually rather than counted: a count would pass if someone
        // swapped one input for another, and each of these is a thing the
        // notification renders.
        listOf("condition", "state", "readiness", "faceSetId", "pairedAddress").forEach {
            assertTrue(
                "`$it` is rendered in the notification but is not a source of its " +
                    "combine — the shade cannot update when it changes.\ncombine args were: " +
                    args.trim(),
                args.contains(it),
            )
        }
    }
}
