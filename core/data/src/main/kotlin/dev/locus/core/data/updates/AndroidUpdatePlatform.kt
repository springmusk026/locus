package dev.locus.core.data.updates

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.locus.core.data.R
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

internal class AndroidUpdatePlatform(private val context: Context) : UpdatePlatform {
    override val supportedAbis: List<String> get() = Build.SUPPORTED_ABIS.toList()

    override val downloadDir: File get() = File(context.noBackupFilesDir, "updates")

    override fun installed(): ApkCheck.Facts {
        val info = context.packageManager.getPackageInfo(context.packageName, signingFlags())
        return facts(info) ?: ApkCheck.Facts(context.packageName, 0, emptySet())
    }

    override fun archive(file: File): ApkCheck.Facts? {
        val info = context.packageManager.getPackageArchiveInfo(file.path, signingFlags()) ?: return null
        return facts(info)
    }

    override fun storeInstaller(): String? {
        val pm = context.packageManager
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        return StoreInstallers.nameOf(installer)
    }

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun install(file: File, packageName: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // Android rejects the session if the APK isn't this app.
            setAppPackageName(packageName)
            setSize(file.length())
            // Always ask the user, even where Android could skip it.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                // Mutable: the installer adds the status extras (required from targetSdk 35, Session.commit).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun notifyAvailable(version: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A newer Locus is on GitHub (only when the update check is on)"
            })
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            it.action = UpdateLinks.ACTION_OPEN
            PendingIntent.getActivity(context, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentTitle("Update available: $version")
                .setContentText("See what's new in Settings → About & privacy")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    override fun clearNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun facts(info: PackageInfo): ApkCheck.Facts? {
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val (signers, history) = if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo ?: return null
            // apkContentsSigners: the current signers only; the history (single signer) adds rotated-from keys.
            val current = s.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            val past = if (s.hasMultipleSigners()) emptySet() else s.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            current to past
        } else {
            val sigs = info.signatures?.map { sha256(it.toByteArray()) }?.toSet() ?: return null
            sigs to sigs
        }
        return ApkCheck.Facts(info.packageName, code, signers, history + signers)
    }

    private fun sha256(bytes: ByteArray): String = ReleaseFiles.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    companion object {
        private const val CHANNEL = "app_updates"
        private const val NOTIFICATION_ID = 0x75706474
    }
}

/** Android's installer reporting back on an update session ([AndroidUpdatePlatform.install]). */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {
    @Inject lateinit var updates: Updates

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The user just tapped Install in Locus, so Locus is in front and may open the installer's prompt.
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirm == null) {
                updates.onInstallResult(PackageInstaller.STATUS_FAILURE, "The installer didn't open")
                return
            }
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { updates.onInstallResult(PackageInstaller.STATUS_FAILURE, it.message) }
            return
        }
        updates.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
