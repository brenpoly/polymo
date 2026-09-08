package com.digitalpet.conversation

import android.content.Context
import com.digitalpet.pet.PetFaceSets
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which voice the pet is using.
 *
 * **Phone-side, unlike the face.** The pet owns how it LOOKS because it draws
 * that itself and must keep doing so with no phone in the room. It does not own
 * how it SPEAKS, because every word comes from here — there is nothing for the
 * firmware to remember and no protocol to add.
 *
 * ### A personality is ONE choice
 *
 * The face and the voice were briefly separate settings, and separate settings
 * let you build a pet whose face and voice read as different characters. They
 * are locked together now: a face set names its persona, and selecting either
 * sets both.
 *
 * This still holds the persona rather than deriving it on every read, because
 * the face can change while no pet is connected — the voice has to be settable
 * on its own even though it is never CHOSEN on its own.
 */
@Singleton
class PetPersonaStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs =
        context.getSharedPreferences("pet_persona", Context.MODE_PRIVATE)

    private val _active = MutableStateFlow(load())
    val active: StateFlow<PetPersona> = _active.asStateFlow()

    fun select(id: String) {
        prefs.edit().putString(KEY, id).apply()
        _active.value = PetPersonas.byId(id) ?: PetPersonas.default
    }

    /**
     * The face changed — adopt its voice, unconditionally.
     *
     * Unconditional because they are one choice. The face can also change from
     * the PET (its own button, or another phone), and the voice following it
     * there is the whole point of them being locked: a pet cannot end up
     * looking like one character and speaking as another.
     */
    fun onFaceSetChanged(faceSetId: String?) {
        val paired = PetFaceSets.byId(faceSetId)?.personaId ?: return
        select(paired)
    }

    private fun load(): PetPersona =
        PetPersonas.byId(prefs.getString(KEY, null)) ?: PetPersonas.default

    private companion object { const val KEY = "id" }
}
