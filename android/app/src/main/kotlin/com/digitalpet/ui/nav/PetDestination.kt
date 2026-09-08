package com.digitalpet.ui.nav

/**
 * Where you can be in this app.
 *
 * **ONE SURFACE AND A SETTINGS TREE**, as of the Claude Design implementation
 * (2026-08-06). DESIGN.md §5.1 originally specified two destinations — Pet and
 * Talk — with the transcript as a tab of its own. The design supersedes that:
 * the transcript is a sheet on the main surface, so there is nothing to
 * navigate *between* at the top level and the bottom bar is gone.
 *
 * §5.1's reasoning survives the change rather than being overturned by it. It
 * argued the transcript is "the one surface that is usually not needed", because
 * a conversation normally runs between the pet's own microphone and speaker with
 * the phone in a pocket. That is an argument for the transcript being
 * *secondary*, and a sheet expresses that better than a tab of equal weight did.
 *
 * Settings is now a small tree rather than one scrolling page, which is what
 * finally kills the `EmbeddedPanel` height workaround: each panel owns a screen
 * and scrolls once. The two chips on the main surface open [SettingsPet] and
 * [SettingsModels] directly — the fastest route to a fix is from the thing that
 * told you it was broken.
 *
 * Routes are a sealed hierarchy so a typo is a compile error rather than a tap
 * that silently does nothing.
 */
sealed class PetDestination(val route: String) {

    /** The whole product: pet, condition, and the conversation in a sheet. */
    data object Pet : PetDestination("pet")

    /**
     * Setup, once — DESIGN.md §5.2.
     *
     * The start destination on a fresh install and nowhere else. It is a route
     * rather than an overlay so that the two steps it cannot complete itself —
     * pairing and models — can push the real settings pages onto the same back
     * stack and come back, which is the whole shape of the flow.
     */
    data object FirstRun : PetDestination("first-run")

    /** The settings index. Reached from the gear. */
    data object Settings : PetDestination("settings")

    /** Pairing and link state — the "Connected" chip's destination. */
    data object SettingsPet : PetDestination("settings/pet")

    /** The three models — the "Models" chip's destination. */
    data object SettingsModels : PetDestination("settings/models")

    /** Tracked apps and their allowances. */
    data object SettingsScreenTime : PetDestination("settings/screen-time")

    /**
     * Light, dark, or whatever the phone says.
     *
     * The first settings page that is about the *app* rather than about the
     * pet — nothing here changes what the pet does, which is why it sits last
     * in an index otherwise ordered by what breaks without it.
     */
    data object SettingsAppearance : PetDestination("settings/appearance")

    /**
     * Every grant the pet needs, in one list.
     *
     * The general answer to a shape that cost three bugs in a day: first run
     * asks for each at the step that needs it and then finishes forever, so a
     * skipped step meant a permanently lost grant with no way back. This is the
     * way back for all of them.
     */
    data object SettingsPermissions : PetDestination("settings/permissions")

}
