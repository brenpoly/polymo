package com.digitalpet.pet

import com.digitalpet.llm.LlmManager

/**
 * The three things a pet needs in order to answer at all.
 *
 * Named as faculties rather than as technologies on purpose. "Whisper is not
 * initialised" describes our problem; "your pet cannot hear you" describes
 * theirs, and this text exists for them.
 */
enum class PetFaculty(
    /** How the pet's owner would say it. */
    val friendly: String,
    /** What is actually missing, for someone about to go and find one. */
    val what: String,
) {
    // Ordered by how badly the pet is broken without it, and the order they are
    // listed in. Without a brain nothing works at all; without ears you can
    // still type to it; without a voice it still writes on its own screen.
    BRAIN("think", "a .gguf language model"),
    EARS("hear you", "a Whisper .bin model"),
    // Both files, because both are what it takes — the voice slot's import
    // asks for the pair and this is the sentence that sends someone to find
    // them. Naming only the `.onnx` sent people back one file short.
    VOICE("speak", "a Piper .onnx voice and its .onnx.json"),
}

/**
 * DESIGN.md §2.1's Axis C, which has been specified since that section was
 * written and represented nowhere.
 *
 * **Its failure mode is silence.** With no models the pet listens, the phone
 * transcribes nothing, and the reply never comes — the documented
 * `SttService not initialised`, visible only in a log. From the outside a
 * perfectly working product looks broken, which is the worst possible first
 * impression for something whose whole premise is that it is alive.
 *
 * **WHEN THIS WINDOW ACTUALLY OPENS — measured, and narrower than it first
 * looked.** Models are singletons, so they are loaded once per PROCESS and
 * survive everything short of the process dying. Verified on device: reopening
 * the app keeps the same pid, reloads nothing, and produces no window at all.
 *
 * So the ~18 s gap belongs to a **fresh process**, not to a reconnect:
 *
 * | Event | Reloads? |
 * |---|---|
 * | Reopening the app | **No** — same process, models still resident |
 * | Pet out of range and back | **No** — the retry machinery keeps the process alive |
 * | Android kills the app for memory | Yes — `START_STICKY` revives the service |
 * | A crash | Yes — same path |
 * | Phone reboot | Nothing runs until the app is opened; there is no BOOT_COMPLETED receiver |
 *
 * The reconnect case only hits it when the process had **already** died, which
 * is why it looked like a reconnect bug. The gap is real and worth closing —
 * during it the pet is connected, listening, and cannot answer — but it is
 * rarer than "every time the pet comes back into range".
 */
sealed interface PetReadiness {
    /** Everything loaded. The UI shows nothing at all. */
    data object Ready : PetReadiness

    /** Transient and expected — a model is being read off disk. */
    data class Loading(val which: PetFaculty) : PetReadiness

    /** A model was found and would not load. Carries the native error verbatim. */
    data class Failed(val which: PetFaculty, val error: String) : PetReadiness

    /** No file for one or more faculties. The first-run case. */
    data class Missing(val which: List<PetFaculty>) : PetReadiness

    companion object {
        /**
         * Fold the three services into one answer.
         *
         * **Precedence: Failed, then Loading, then Missing.** A failure needs a
         * decision, loading resolves itself, and missing files need a trip to a
         * file manager — so the most urgent thing that is true wins, in the
         * order the user can act on it.
         *
         * **STT can report [Failed] as of 2026-08-05**, and could not before. Both
         * `restoreStt` and `loadSttModel` caught their exception and logged it,
         * leaving the name null — so a Whisper model that was present and broken
         * was indistinguishable from one that was never installed, and this
         * function told the user to add a model they already had. Fixed at the
         * source, where the exception is, rather than guessed at here.
         *
         * **BRAIN's failure outranks EARS's**, on the same severity ordering as
         * [PetFaculty]: with no brain nothing works at all, and reporting the
         * lesser of two simultaneous failures would send someone to fix the one
         * that was not stopping them.
         */
        fun of(
            llm: LlmManager.ModelState,
            ttsReady: Boolean,
            sttModelName: String?,
            sttError: String? = null,
        ): PetReadiness {
            if (llm is LlmManager.ModelState.Error) {
                return Failed(PetFaculty.BRAIN, llm.message)
            }
            if (sttError != null) {
                return Failed(PetFaculty.EARS, sttError)
            }
            if (llm is LlmManager.ModelState.Loading) {
                return Loading(PetFaculty.BRAIN)
            }

            val missing = buildList {
                if (llm !is LlmManager.ModelState.Ready) add(PetFaculty.BRAIN)
                if (sttModelName == null) add(PetFaculty.EARS)
                if (!ttsReady) add(PetFaculty.VOICE)
            }
            return if (missing.isEmpty()) Ready else Missing(missing)
        }

        /**
         * One line for the pet screen, or **null when everything is ready**.
         *
         * Absent rather than a reassuring green tick, per DESIGN.md §5.3: a
         * working product should not spend a row of its home screen telling you
         * it is working.
         */
        fun headline(r: PetReadiness): String? = when (r) {
            is Ready -> null
            is Loading -> "Waking up…"
            is Failed -> "Your pet cannot ${r.which.friendly}."
            is Missing -> when (r.which.size) {
                PetFaculty.entries.size -> "Your pet has no models yet."
                1 -> "Your pet cannot ${r.which[0].friendly}."
                else -> "Your pet cannot " +
                    r.which.dropLast(1).joinToString(", ") { it.friendly } +
                    " or ${r.which.last().friendly}."
            }
        }

        /**
         * What the PET should show on its own screen, or null when it can answer.
         *
         * **This is the surface that exists when the app does not.** The
         * foreground service comes up on its own when a pet reconnects after
         * being out of range, so the common case for "cannot answer yet" has no
         * phone screen in it at all — and the pet otherwise sits there looking
         * perfectly well while silently ignoring everything said to it.
         *
         * In the pet's own voice rather than the app's, because it is the pet
         * saying it. Short: the screen fits about six lines and the wire caps a
         * message at 240 bytes.
         */
        fun petText(r: PetReadiness): String? = when (r) {
            is Ready -> null
            is Loading -> "just waking up..."
            is Failed -> "something's wrong with me - check the app"
            is Missing -> when (r.which.size) {
                PetFaculty.entries.size -> "i have no models yet - check the app"
                else -> "i can't ${r.which.joinToString(" or ") { it.friendly }} yet"
            }
        }

        /**
         * What to do about it, or null when there is nothing to do.
         *
         * Names the missing file **by kind**, because "add a model" sends
         * someone to a search engine and "a .gguf language model" sends them to
         * the right file.
         */
        fun action(r: PetReadiness): String? = when (r) {
            is Ready -> null
            // "Loading its a .gguf language model" — `what` already carries
            // its own article, which only showed up in real output.
            is Loading -> "Loading ${r.which.what}."
            // The native error verbatim: a paraphrase of a loader failure has
            // thrown away the only thing that identifies it.
            is Failed -> "Its ${r.which.what} would not load — ${r.error}"
            is Missing -> "Add " + r.which.joinToString(", ") { it.what } +
                " in Settings."
        }
    }
}
