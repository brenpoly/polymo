package com.digitalpet.ui.theme

import com.digitalpet.ble.PetBleRepository
import com.digitalpet.ble.PetProtocol
import com.digitalpet.pet.FirstRun
import com.digitalpet.pet.FirstRunStep
import com.digitalpet.pet.PetFaculty
import com.digitalpet.pet.PetReadiness
import com.digitalpet.pet.PetStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The strings the code produces, against `design-system/strings.txt`.
 *
 * **Copy is design, and it was the last part of the design system with nothing
 * checking it.** `TokenSyncTest` covers colour, spacing, shape and type;
 * `ComponentRosterTest` covers what exists. A word could still change on either
 * side and nothing would notice.
 *
 * ### Why only some strings
 *
 * The manifest holds the ones that carry a *decision*. The design system
 * publishes the connection labels and the care-card headlines as **ordered**
 * tables, and the order is the design: "Your pet is off" outranks "Bluetooth is
 * off" outranks "Connected", because being off is the only one of the three the
 * user chose, and reporting the radio instead sends them to hunt a pet they
 * switched off on purpose. Pinning those pins the reasoning.
 *
 * A subtitle nobody ranks is not here. A test that pinned every string would
 * fail on every typo fix and be deleted within a month.
 *
 * ### What this is NOT for
 *
 * It cannot tell you a string is *rendered* correctly. `StatusChip` produced a
 * perfect `"Connected"` for a week and drew `"Connect"`, because its label was
 * allocated half a chip and `Text` clips by default. That is `TruncationTest`,
 * and the two failures look identical from the outside — which is exactly why
 * the string was suspected first and was innocent.
 */
class StringSyncTest {

    private fun manifest(): Map<String, String> {
        var dir: File? = File("").absoluteFile
        var file: File? = null
        repeat(5) {
            val c = File(dir, "design-system/strings.txt")
            if (c.isFile) { file = c; return@repeat }
            dir = dir?.parentFile
        }
        val f = file ?: throw AssertionError(
            "design-system/strings.txt not found from ${File("").absolutePath} — " +
                "this test reads it, so a wrong path would make every assertion vacuous."
        )
        return f.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('|') }
            .associate { line ->
                val i = line.indexOf('|')
                line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
    }

    private fun expect(key: String): String =
        manifest()[key] ?: throw AssertionError("no `$key` in design-system/strings.txt")

    private fun condition(
        satiety: Int = 4,
        happiness: Int = 4,
        dead: Boolean = false,
        sick: Boolean = false,
        calling: Boolean = false,
    ) = PetProtocol.Condition(
        satiety = satiety,
        happiness = happiness,
        careMistakes = 0,
        dead = dead,
        sick = sick,
        calling = calling,
    )

    @Test
    fun `the manifest is readable and not empty`() {
        // Prove the instrument: an unparsed manifest would make `expect` throw
        // rather than silently pass, but an empty one would make this file look
        // like coverage it is not.
        val m = manifest()
        assertTrue("parsed ${m.size} strings, expected at least 20", m.size >= 20)
        assertTrue("no connection labels parsed", m.keys.any { it.startsWith("connection.") })
    }

    @Test
    fun `connection labels match, in the design system's precedence order`() {
        val ready = PetBleRepository.State.READY
        /*
         * The first two lines assert the RANKING as well as the wording, by
         * holding every lower-ranked condition true underneath them: "Your pet
         * is off" while the radio is ALSO off and the link is ALSO ready.
         *
         * The first version passed `bluetoothOn = true` there, which reads like
         * a ranking assertion and is not one — with the radio on, the branch
         * below it is false anyway, so swapping the two branches still passed.
         * `PetStatusTextTest` caught that inversion; this file did not, while
         * its comment claimed it did.
         */
        assertEquals(
            expect("connection.off"),
            PetStatusText.connectionLabel(ready, paired = true, bluetoothOn = false, switchedOff = true),
        )
        assertEquals(
            expect("connection.bluetooth-off"),
            PetStatusText.connectionLabel(ready, paired = true, bluetoothOn = false),
        )
        assertEquals(expect("connection.ready"), PetStatusText.connectionLabel(ready, paired = true))
        assertEquals(
            expect("connection.connecting"),
            PetStatusText.connectionLabel(PetBleRepository.State.CONNECTING, paired = true),
        )
        assertEquals(
            expect("connection.scanning"),
            PetStatusText.connectionLabel(PetBleRepository.State.SCANNING, paired = true),
        )
        assertEquals(
            expect("connection.paired-idle"),
            PetStatusText.connectionLabel(PetBleRepository.State.IDLE, paired = true),
        )
        assertEquals(
            expect("connection.never-paired"),
            PetStatusText.connectionLabel(PetBleRepository.State.IDLE, paired = false),
        )
    }

    @Test
    fun `care card headlines match, in the design system's precedence order`() {
        assertEquals(
            expect("headline.off"),
            PetStatusText.headline(condition(), switchedOff = true),
        )
        assertEquals(expect("headline.unknown"), PetStatusText.headline(null))
        assertEquals(expect("headline.dead"), PetStatusText.headline(condition(dead = true, sick = true)))
        assertEquals(expect("headline.sick"), PetStatusText.headline(condition(sick = true)))
        assertEquals(
            expect("headline.sick-discreet"),
            PetStatusText.headline(condition(sick = true), discreet = true),
        )
        assertEquals(
            expect("headline.starving-miserable"),
            PetStatusText.headline(condition(satiety = 0, happiness = 0)),
        )
        assertEquals(expect("headline.starving"), PetStatusText.headline(condition(satiety = 0)))
        assertEquals(expect("headline.miserable"), PetStatusText.headline(condition(happiness = 0)))
        assertEquals(expect("headline.calling"), PetStatusText.headline(condition(calling = true)))
        assertEquals(expect("headline.low"), PetStatusText.headline(condition(satiety = 2)))
        assertEquals(expect("headline.well"), PetStatusText.headline(condition()))
    }

    @Test
    fun `readiness headlines match, including the two-faculty phrasing`() {
        assertEquals(expect("readiness.loading"), PetReadiness.headline(PetReadiness.Loading(PetFaculty.BRAIN)))
        assertEquals(
            expect("readiness.none"),
            PetReadiness.headline(PetReadiness.Missing(PetFaculty.entries)),
        )
        assertEquals(
            expect("readiness.one-missing"),
            PetReadiness.headline(PetReadiness.Missing(listOf(PetFaculty.EARS))),
        )
        // The design's own example is "Your pet cannot hear you or speak." —
        // the joining word is the part most likely to be lost in a rewrite.
        assertEquals(
            expect("readiness.two-missing"),
            PetReadiness.headline(PetReadiness.Missing(listOf(PetFaculty.EARS, PetFaculty.VOICE))),
        )
    }

    @Test
    fun `appearance options match`() {
        assertEquals(expect("appearance.system"), ThemeMode.SYSTEM.label)
        assertEquals(expect("appearance.light"), ThemeMode.LIGHT.label)
        assertEquals(expect("appearance.dark"), ThemeMode.DARK.label)
    }

    @Test
    fun `the screen-time row says which session it is showing`() {
        /*
         * Pinned because this row's number has meant four different things — the
         * day's total, the live sitting, the longest session today, and the most
         * recent one — and every version rendered as a bare `n / m min` with
         * nothing on the row saying which. Three of the four were reported as
         * the feature being broken.
         */
        assertEquals(
            expect("screentime.session-label"),
            com.digitalpet.data.ScreenTimeDisplay.SESSION_LABEL,
        )
    }

    @Test
    fun `the models chip keeps the design's wording, asymmetry and all`() {
        /*
         * The design gives this chip a NOUN in its good state — "Models" — next
         * to a chip that gives a STATE, "Connected". That reads oddly and it is
         * the design's call; the code mirrors it rather than quietly improving
         * it, because §7.2 puts copy on the design system's side.
         *
         * Pinned here so that "improving" it becomes a deliberate act with a
         * design-system change attached, rather than a one-word commit.
         */
        val screen = File(
            manifestDir(), "android/app/src/main/kotlin/com/digitalpet/ui/screens/PetHomeScreen.kt"
        ).readText()
        assertTrue(
            "the models chip no longer says \"${expect("chip.models-ready")}\"",
            screen.contains("\"${expect("chip.models-ready")}\""),
        )
        assertTrue(
            "the models chip no longer says \"${expect("chip.models-missing")}\"",
            screen.contains("\"${expect("chip.models-missing")}\""),
        )
    }


    @Test
    fun `first run's titles and actions match`() {
        val steps = listOf(
            FirstRunStep.WELCOME to "welcome",
            FirstRunStep.PET to "pet",
            FirstRunStep.MODELS to "models",
            FirstRunStep.SCREEN_TIME to "screentime",
            FirstRunStep.NOTIFICATIONS to "notifications",
        )
        steps.forEach { (step, key) ->
            assertEquals(expect("firstrun.title.$key"), FirstRun.title(step))
            assertEquals(expect("firstrun.action.$key"), FirstRun.action(step))
        }
    }

    @Test
    fun `THE WAY OUT SAYS WHAT DECLINING COSTS, and the two wordings do not swap`() {
        /*
         * The pinned part is not the words, it is which word goes with which
         * kind of step. Steps 2 and 3 BLOCK — there is no product without a pet
         * or without models — so declining ends the run, and "I'll finish
         * setting up later" says so. Steps 4 and 5 DEGRADE: the step comes back,
         * and "Not now" is honest about that.
         *
         * Swapping them would be a lie about what the button does, and it would
         * compile, and it would look fine.
         */
        val blocking = expect("firstrun.secondary.blocking")
        val optional = expect("firstrun.secondary.optional")

        assertEquals(blocking, FirstRun.secondary(FirstRunStep.PET))
        assertEquals(blocking, FirstRun.secondary(FirstRunStep.MODELS))
        assertEquals(optional, FirstRun.secondary(FirstRunStep.SCREEN_TIME))
        assertEquals(optional, FirstRun.secondary(FirstRunStep.NOTIFICATIONS))

        // And the wording tracks `optional` rather than being written down twice.
        FirstRunStep.entries.filter { FirstRun.secondary(it) != null }.forEach { step ->
            val expected = if (step.optional) optional else blocking
            assertEquals("$step's way out does not match its blocking-ness", expected, FirstRun.secondary(step))
        }

        // The welcome offers none, on purpose.
        assertEquals(null, FirstRun.secondary(FirstRunStep.WELCOME))
    }

    @Test
    fun `the cost is stated only where something is actually lost`() {
        assertEquals(expect("firstrun.cost.screentime"), FirstRun.cost(FirstRunStep.SCREEN_TIME))
        assertEquals(expect("firstrun.cost.notifications"), FirstRun.cost(FirstRunStep.NOTIFICATIONS))
        // Not on a blocking step: the body has already said the product does not
        // work, and repeating it under the button is nagging rather than telling.
        listOf(FirstRunStep.WELCOME, FirstRunStep.PET, FirstRunStep.MODELS).forEach {
            assertEquals("$it should state no cost", null, FirstRun.cost(it))
        }
    }

    private fun manifestDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            if (File(dir, "design-system/strings.txt").isFile) return dir!!
            dir = dir?.parentFile
        }
        throw AssertionError("repo root not found from ${File("").absolutePath}")
    }
}
