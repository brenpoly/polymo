package com.digitalpet.pet

import com.digitalpet.pet.PermissionInventory.Grant
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Permissions page is a CONSENT surface, and its copy is checkable.
 *
 * The page shipped once in the codebase's ordinary house voice — "See what
 * arrives", "Connect to your pet" — which names a thing as its owner would say
 * it. That is right for saying what is broken and wrong for asking somebody to
 * hand over a capability: "See what arrives" omits that the app can read every
 * notification on the phone. These tests pin the properties that stop it
 * drifting back.
 *
 * One of them reads `AndroidManifest.xml`, which puts it with `ContrastTest`
 * and `TokenSyncTest` in the small set that check something a compiler cannot:
 * **a sentence on screen against the fact it claims.**
 */
class PermissionCopyTest {

    @Test
    fun `the no-internet claim is true, and this is what keeps it true`() {
        // The page says "None of this can leave your phone. PolyMO has no
        // internet permission at all". Adding INTERNET later would turn a
        // verifiable statement into a false one, on the one screen where a
        // person is deciding what to trust the app with. Nothing else in the
        // build would notice.
        val manifest = manifest().readText()
        assertTrue(
            "AndroidManifest declares INTERNET, which makes NOTHING_LEAVES a lie " +
                "on the Permissions page. Either drop the permission or rewrite " +
                "PermissionInventory.NOTHING_LEAVES to tell the truth.",
            !manifest.contains("android.permission.INTERNET"),
        )
        assertTrue(!manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertTrue(PermissionInventory.NOTHING_LEAVES.contains("no internet permission"))
    }

    @Test
    fun `the neverForLocation claim matches the manifest`() {
        // The scan row tells people Android will not let this be used to work
        // out where they are. That is only true while the flag is on the
        // permission, and the flag is one attribute somebody could drop while
        // debugging a scan problem.
        val manifest = manifest().readText()
        assertTrue(
            "BLUETOOTH_SCAN is no longer neverForLocation, so the Permissions " +
                "page is making a promise the manifest does not keep.",
            manifest.contains("neverForLocation"),
        )
        assertTrue(Grant.BLUETOOTH_SCAN.use.contains("never for location"))
    }

    @Test
    fun `every row says what the grant ALLOWS, not only what breaks`() {
        // The consent half. Without it a granted row tells somebody nothing
        // about what they allowed — which is most of what this page is for.
        Grant.entries.forEach { g ->
            assertTrue("${g.name} does not say what it allows", g.allows.isNotBlank())
            assertTrue("${g.name} does not say what it is used for", g.use.isNotBlank())
            assertTrue("${g.name} does not say what breaks", g.consequence.isNotBlank())
        }
    }

    @Test
    fun `what it allows is stated as a capability, in the second person`() {
        // "Lets PolyMO …" — an active sentence naming the actor and the power.
        // A passive or feature-shaped line is how "See what arrives" happened.
        Grant.entries.forEach { g ->
            assertTrue(
                "${g.name} does not name the app as the actor: ${g.allows}",
                g.allows.startsWith("Lets PolyMO"),
            )
        }
    }

    @Test
    fun `the broadest grant says that it is the broadest`() {
        // Reading every notification is not comparable to the other five, and a
        // page that presents six equal-looking rows implies it is.
        assertTrue(
            Grant.NOTIFICATION_ACCESS.allows,
            Grant.NOTIFICATION_ACCESS.allows.contains("broadest"),
        )
        // And it must not soften what it can see.
        assertTrue(Grant.NOTIFICATION_ACCESS.allows.contains("every notification"))
        assertTrue(Grant.NOTIFICATION_ACCESS.allows.contains("what it says"))
    }

    @Test
    fun `usage access admits Android grants it for every app`() {
        // The gap between the ceiling and what this app reads is the honest
        // part. Stating only "the apps you chose" would be flattering and false.
        assertTrue(Grant.USAGE_ACCESS.allows, Grant.USAGE_ACCESS.allows.contains("every app"))
        assertTrue(Grant.USAGE_ACCESS.use.contains("chose to track"))
    }

    @Test
    fun `no title hides the capability behind the feature it powers`() {
        // The exact regression this page shipped with: titles that named the
        // pet's benefit instead of the permission. A title mentioning the pet is
        // describing the feature, not the grant.
        Grant.entries.forEach { g ->
            assertTrue(
                "${g.name} titles the feature rather than the capability: ${g.title}",
                !g.title.contains("pet", ignoreCase = true),
            )
        }
    }

    /** Same walk-up-from-the-working-directory trick as `TokenSyncTest`. */
    private fun manifest(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/AndroidManifest.xml")
            if (candidate.exists()) return candidate
            val nested = File(dir, "android/app/src/main/AndroidManifest.xml")
            if (nested.exists()) return nested
            dir = dir.parentFile
        }
        throw AssertionError("Cannot find AndroidManifest.xml from ${File("").absolutePath}")
    }

    @Test
    fun `a missing grant starts open and a held one starts closed`() {
        // The tension in collapsing a consent surface, and its resolution. The
        // descriptions are what make this more than a checklist, so hiding them
        // by default would undo the point — but they matter at the moment of
        // DECIDING, and that is when the grant is missing. A held row is a
        // decision already made, one tap from being re-read.
        assertTrue("a missing grant must explain itself", PermissionInventory.startsExpanded(false))
        assertTrue("a held grant should rest quiet", !PermissionInventory.startsExpanded(true))
    }

    @Test
    fun `the subtitle describes the phone, not the pet`() {
        // It read "What the pet is allowed to do", which names the toy rather
        // than the device and makes a consent page sound like a feature list.
        assertTrue(
            PermissionInventory.SUBTITLE,
            !PermissionInventory.SUBTITLE.contains("pet", ignoreCase = true),
        )
        assertTrue(PermissionInventory.SUBTITLE.contains("allowed"))
        // Both halves, because the page shows both.
        assertTrue(PermissionInventory.SUBTITLE.contains("what you have not"))
    }
}
