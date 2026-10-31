package dev.locus.core.data.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.locus.core.model.LocalNetwork

/** Android 17's local network permission, as seen from code with no UI to ask from. */
object LocalNetworkAccess {
    /** True below Android 17, where the permission isn't enforced, or once the user allowed it. */
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < LocalNetwork.ENFORCED_FROM_SDK ||
            ContextCompat.checkSelfPermission(context, LocalNetwork.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * What to add to a failure reaching [target] when the missing permission is the likely cause,
     * or null. Android blocks such connections without a clear error (TCP usually times out).
     */
    fun blockedHint(context: Context, target: String, name: String = LocalNetwork.hostOf(target).orEmpty()): String? =
        if (!LocalNetwork.isLocalTarget(target) || granted(context)) null
        else "$name is on your local network, and Android blocks Locus from local devices until you allow " +
            "Nearby devices. Allow it in Settings → Apps → Locus → Permissions → Nearby devices, then try again."
}
