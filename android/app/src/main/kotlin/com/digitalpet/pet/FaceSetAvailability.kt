package com.digitalpet.pet

/**
 * Why the face cannot be changed right now, when it cannot.
 *
 * ### Why this exists at all
 *
 * The Face screen moves its radio button only when the pet confirms the change,
 * which is right — the pet owns what it wears, and a screen that moved its own
 * radio would show a face the pet is not wearing. But it means a request that
 * never lands looks *exactly* like a dead row, and the first time this ran on
 * hardware that is precisely what happened: taps registered, `selectFaceSet` was
 * called eight times, and nothing moved.
 *
 * The cause was not the firmware and not the app. **Android caches GATT service
 * discovery per bonded device**, so a characteristic added to the firmware is
 * invisible to a phone that has already paired. The pet was running v9 and
 * saying so; the phone was reading a service list from before v9 existed.
 *
 * That is unguessable from the outside, and "this pet does not support face
 * sets" would have been a lie that sent someone looking in the wrong place. So
 * the three reasons are separated and each says what to actually do.
 */
enum class FaceSetAvailability {
    /** Selectable. */
    AVAILABLE,

    /** No pet on the other end, so there is nowhere for a choice to go. */
    DISCONNECTED,

    /**
     * The pet reports v9 or later and the characteristic is still missing, which
     * can only be Android's cached service list. Re-pairing drops it.
     */
    STALE_CACHE,

    /** The pet is genuinely older than face sets. */
    UNSUPPORTED;

    val selectable: Boolean get() = this == AVAILABLE

    /**
     * What to tell the user, or null when there is nothing to explain.
     *
     * Each names the action that fixes it. [STALE_CACHE] deliberately does not
     * mention firmware or versions: the user's pet is fine and their app is
     * fine, and the only useful sentence is the one about re-pairing.
     */
    val message: String?
        get() = when (this) {
            AVAILABLE -> null
            DISCONNECTED -> "Connect your pet to change its face."
            STALE_CACHE ->
                "Your pet offers more faces than this phone can see. " +
                    "Forget the pet in Bluetooth settings and pair it again."
            UNSUPPORTED ->
                "This pet's firmware is older than face sets. " +
                    "Update it to change how it looks."
        }

    companion object {
        /**
         * [connected] is the BLE link; [characteristicPresent] is whether this
         * phone actually discovered the face characteristic; [petProtocol] is
         * the version the pet reported, or null if it has not said yet.
         *
         * The order matters. A disconnected pet has no protocol version and no
         * characteristics, so every other reason would also be "true" and the
         * least useful of them would win.
         */
        fun of(
            connected: Boolean,
            characteristicPresent: Boolean,
            petProtocol: Int?,
        ): FaceSetAvailability = when {
            !connected -> DISCONNECTED
            characteristicPresent -> AVAILABLE
            // It told us it speaks v9 and the characteristic is missing anyway.
            // Nothing but a cached service list can produce that.
            (petProtocol ?: 0) >= FACE_SETS_FROM -> STALE_CACHE
            else -> UNSUPPORTED
        }

        /** The protocol version that introduced the face characteristic. */
        const val FACE_SETS_FROM = 9
    }
}
