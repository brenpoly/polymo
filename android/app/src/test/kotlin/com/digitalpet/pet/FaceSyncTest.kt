package com.digitalpet.pet

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated face sets against the JSON they were generated from.
 *
 * ### The failure this exists for
 *
 * Face geometry used to be hand-copied between C, Kotlin and XML, and the old
 * `PetFaceTest` pinned it as literals so drift failed the build. Generation
 * removed that drift — and introduced a new one in its place: **someone edits
 * `design-system/faces/` and forgets to run `tools/gen-faces.py`.** Nothing
 * about that looks wrong. The build is green, the app runs, and the pet simply
 * wears the previous version of a face somebody thinks they changed.
 *
 * So this reads the JSON at test time and compares it to the compiled constants.
 * It is one of the file-reading tests for the same reason the others are: a
 * property that is *arithmetic* never needed eyes, and nothing else in the build
 * can see a stale generator.
 *
 * ### Why it does not just re-run the generator
 *
 * It could, and then it would pass whatever the generator produced — which is a
 * test of nothing. Comparing the compiled output to the source input keeps the
 * two things independent, which is the lesson this repo keeps relearning: **a
 * check is only as strong as the independence of the two things it compares.**
 *
 * The C header and the drawables are not checked here — a JVM test cannot see
 * them — so `tools/gen-faces.py --check` covers those and is what
 * `tools/design-sync.sh` runs.
 */
class FaceSyncTest {

    private fun facesDir(): File {
        var d: File? = File("").absoluteFile
        repeat(5) {
            val c = File(d, "design-system/faces")
            if (c.isDirectory) return c
            d = d?.parentFile
        }
        throw AssertionError(
            "design-system/faces not found from ${File("").absolutePath} — this test " +
                "reads the directory, so a wrong path would make it pass by finding nothing."
        )
    }

    /** Every `"key": value` integer in one JSON object, flat. Deliberately not a
     *  real parser: a dependency-free scrape is enough for a file this shape and
     *  keeps the comparison independent of any library the app already trusts. */
    private fun ints(block: String): Map<String, Int> =
        Regex("\"(\\w+)\"\\s*:\\s*(-?\\d+)").findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }

    private fun face(json: String, slot: String): Map<String, Int> {
        val m = Regex("\"$slot\"\\s*:\\s*\\{([^}]*)\\}").find(json)
            ?: throw AssertionError("no '$slot' face in the JSON")
        return ints(m.groupValues[1])
    }

    private fun hex(json: String, key: String): Long {
        val m = Regex("\"$key\"\\s*:\\s*\"#([0-9A-Fa-f]{6})\"").find(json)
            ?: throw AssertionError("no colour '$key' in the JSON")
        return m.groupValues[1].toLong(16) or 0xFF000000L
    }

    private fun sets(): List<Pair<String, String>> =
        facesDir().listFiles { f -> f.extension == "json" }!!
            .sortedBy { it.name }
            .map { it.nameWithoutExtension to it.readText() }

    @Test
    fun `THE FIXTURE READS SOMETHING, or every assertion below is vacuous`() {
        /*
         * The guard that stops this test passing by finding nothing — which is
         * exactly how two worthless tests got into this repo before. If the
         * scrape silently returned empty maps, every comparison would compare
         * nothing to nothing and stay green through any amount of drift.
         */
        val found = sets()
        assertTrue("no face sets found on disk", found.size >= 2)
        val (_, classic) = found.first { it.first == "classic" }
        val neutral = face(classic, "neutral")
        assertEquals("the scrape did not read classic's neutral face", 5, neutral.size)
        assertEquals(74, neutral["eyeW"])
    }

    @Test
    fun `every set on disk is compiled in`() {
        val onDisk = sets().map { it.first }.toSet()
        val compiled = PetFaceSets.all.map { it.id }.toSet()
        assertEquals(
            "design-system/faces and PetFaceSets disagree — run tools/gen-faces.py",
            onDisk, compiled,
        )
    }

    @Test
    fun `every compiled face matches the JSON it came from`() {
        // Only the five the app draws: `sleepy` and `surprised` are the pet's
        // own and are deliberately not in the Kotlin. See PetFace.
        val appFaces = mapOf(
            "happy" to PetFace.HAPPY,
            "neutral" to PetFace.NEUTRAL,
            "sad" to PetFace.SAD,
            "sick" to PetFace.SICK,
            "dead" to PetFace.DEAD,
        )
        sets().forEach { (id, json) ->
            val set = PetFaceSets.byId(id)
                ?: throw AssertionError("$id is on disk but not compiled in")
            appFaces.forEach { (slot, face) ->
                val want = face(json, slot)
                val got = set[face]
                assertEquals("$id/$slot eyeW", want["eyeW"], got.eyeWidth)
                assertEquals("$id/$slot eyeH", want["eyeH"], got.eyeHeight)
                assertEquals("$id/$slot mouthW", want["mouthW"], got.mouthWidth)
                assertEquals("$id/$slot thickness",
                    want["mouthThickness"], got.mouthThickness)
                /*
                 * THE SPIRAL, which is a shape rather than a number and so is
                 * checked from the JSON's own word for it. An `eyeShape` that
                 * said "spiral" while the compiled face drew a lozenge is the
                 * exact staleness this test exists for, and it would be
                 * invisible everywhere else: the build is green either way and
                 * the pet simply is not ill-looking.
                 */
                val spiral = Regex(
                    "\"$slot\"\\s*:\\s*\\{[^}]*\"eyeShape\"\\s*:\\s*\"spiral\""
                ).containsMatchIn(json)
                assertEquals("$id/$slot spiral", spiral, got.isSpiral)
                assertEquals("$id/$slot spiral turns",
                    want["spiralTurns"] ?: 0, got.eyeSpiralTurns)
                // Absent means the set-level default, which is 0 — nothing but
                // a spiral turns, and the generator refuses a spinMs on
                // anything else.
                assertEquals("$id/$slot spin", want["spinMs"] ?: 0, got.spinMs)
                /*
                 * The BULGE is what the JSON states; the radius and angles are
                 * derived from it by the generator. Checking the derivation
                 * here would mean reimplementing the trig in the test, which
                 * proves nothing — so this checks the SIGN agrees: a set that
                 * asks for a smile must not compile to a frown, and a flat
                 * mouth must not compile to a curve.
                 */
                val bulge = want["mouthBulge"]!!
                assertEquals("$id/$slot flat vs curved", bulge != 0, got.isArc)
                if (bulge != 0) {
                    val downward = got.arcStart in 1..179
                    assertEquals("$id/$slot smiles vs frowns", bulge > 0, downward)
                }
            }
        }
    }

    @Test
    fun `every compiled layout and colour matches the JSON`() {
        sets().forEach { (id, json) ->
            val set = PetFaceSets.byId(id)!!
            val layout = ints(
                Regex("\"layout\"\\s*:\\s*\\{([^}]*)\\}").find(json)!!.groupValues[1]
            )
            assertEquals("$id eyeOffsetX", layout["eyeOffsetX"], set.eyeOffsetX)
            assertEquals("$id eyeOffsetY", layout["eyeOffsetY"], set.eyeOffsetY)
            assertEquals("$id mouthOffsetY", layout["mouthOffsetY"], set.mouthOffsetY)
            assertEquals("$id eyeRadius", layout["eyeRadius"], set.eyeRadius)

            val stage = ints(
                Regex("\"stageScale\"\\s*:\\s*\\{([^}]*)\\}").find(json)!!.groupValues[1]
            )
            assertEquals("$id egg", stage["egg"], set.stageScaleEgg)
            assertEquals("$id child", stage["child"], set.stageScaleChild)
            assertEquals("$id teen", stage["teen"], set.stageScaleTeen)
            assertEquals("$id adult", stage["adult"], set.stageScaleAdult)

            // Colours are compared as ARGB longs because that is what Compose
            // holds; the JSON is RGB, so the alpha is added rather than assumed.
            assertEquals("$id eye colour", hex(json, "eye"), set.eyeColor.value.toLong() ushr 32)
            assertEquals("$id mouth colour", hex(json, "mouth"), set.mouthColor.value.toLong() ushr 32)
            assertEquals("$id panel colour", hex(json, "panel"), set.panelColor.value.toLong() ushr 32)
            assertEquals("$id text colour", hex(json, "text"), set.textColor.value.toLong() ushr 32)
        }
    }

    @Test
    fun `classic is first, because index 0 is the firmware's fallback`() {
        // The firmware falls back to pet_face_sets[0] when NVS names a set it
        // does not have. If the generator stopped sorting classic first, a pet
        // with no stored choice would come back wearing a different face.
        assertEquals("classic", PetFaceSets.all.first().id)
    }
}
