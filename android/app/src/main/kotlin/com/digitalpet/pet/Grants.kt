package com.digitalpet.pet

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import com.digitalpet.service.PetNotificationListener

/**
 * Whether each grant is actually held, right now.
 *
 * **One definition, because three surfaces ask**: first run, the two recovery
 * rows on *Your pet*, and the Permissions page. The bugs this repo has paid for
 * are almost all two things that disagreed — a lister and an importer about
 * where voices live, a badge and a loader about whether a model was loaded — and
 * "is this granted" is exactly that shape. Anything reading it a fourth way is a
 * bug waiting.
 *
 * Not unit-tested and cannot be: every line is a platform call. The part that
 * carries a decision — order, wording, what has an action — is in
 * [PermissionInventory], which is pure and is tested.
 */
object Grants {

    fun held(context: Context, grant: PermissionInventory.Grant): Boolean = when (grant) {
        // Below Android 12 these two do not exist as runtime permissions and the
        // old location-based pair covered scanning. Reporting them as missing on
        // an older phone would send someone hunting for a switch that is not
        // there — `FirstRunScreen.bluetoothPermissions` makes the same check for
        // the same reason.
        PermissionInventory.Grant.BLUETOOTH_CONNECT ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                granted(context, Manifest.permission.BLUETOOTH_CONNECT)

        PermissionInventory.Grant.BLUETOOTH_SCAN ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                granted(context, Manifest.permission.BLUETOOTH_SCAN)

        PermissionInventory.Grant.RECORD_AUDIO ->
            granted(context, Manifest.permission.RECORD_AUDIO)

        // Not enforced before 13, so it is held by definition there.
        PermissionInventory.Grant.POST_NOTIFICATIONS ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(context, Manifest.permission.POST_NOTIFICATIONS)

        PermissionInventory.Grant.NOTIFICATION_ACCESS ->
            PetNotificationListener.isEnabled(context)

        // An APPOP, not a permission — `checkSelfPermission` returns granted for
        // PACKAGE_USAGE_STATS whether or not the user has actually allowed it,
        // because the manifest entry only makes the app ELIGIBLE. Reading it the
        // obvious way would report this as held on every device.
        PermissionInventory.Grant.USAGE_ACCESS -> {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            appOps != null && appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Every grant's state in one read, in [PermissionInventory.Grant] order. */
    fun all(context: Context): Map<PermissionInventory.Grant, Boolean> =
        PermissionInventory.Grant.entries.associateWith { held(context, it) }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
