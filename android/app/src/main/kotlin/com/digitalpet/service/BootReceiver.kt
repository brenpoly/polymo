package com.digitalpet.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Brings the pet back after the phone reboots.
 *
 * Without this, a reboot orphans the pet completely: nothing runs until the app
 * is next opened, so it ages, decays, empties and can accrue care mistakes with
 * the phone sitting right beside it doing nothing. For a product whose premise
 * is *a pet you keep alive*, dying because the phone installed an update
 * overnight is the wrong failure.
 *
 * **It does nothing at all when no pet has ever been paired.** An app that
 * launches itself on boot to do nothing is exactly the behaviour people
 * reasonably resent, and pairing is the statement of intent that earns it.
 *
 * Reads the address straight out of prefs rather than injecting
 * `PetBleRepository`. A receiver should decide whether it has any business
 * running *before* building an object graph — and this one usually has none.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Mirrors PetBleRepository's own storage. Duplicated deliberately: the
        // alternative is constructing the BLE stack inside a broadcast receiver
        // to read one string.
        val paired = context
            .getSharedPreferences("digital_pet_prefs", Context.MODE_PRIVATE)
            .getString("pet_ble_address", null)

        if (paired == null) {
            Log.i(TAG, "boot: no pet paired, staying out of the way")
            return
        }

        Log.i(TAG, "boot: a pet is paired, bringing the service up to reconnect")
        ContextCompat.startForegroundService(
            context,
            Intent(context, PetForegroundService::class.java).apply {
                action = PetForegroundService.ACTION_BOOT
            }
        )
    }

    private companion object {
        /**
         * NOT "BootReceiver". Android's own `BootReceiver` in system_server logs
         * under that exact tag — fsck results, dropbox copies — so anything
         * grepping for it gets the platform's boot chatter mixed in with ours.
         * Cost a real reboot's worth of confusion: our line was absent and the
         * system's were present, which reads at a glance like ours had run.
         */
        const val TAG = "PetBootReceiver"
    }
}
