package com.zetaforge.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.zetaforge.app.ZetaForgeApp

/**
 * Where the package installer reports back.
 *
 * Installing is asynchronous and mostly out of the app's hands: the session is
 * committed here, the system asks the user to confirm, and the outcome arrives
 * as a broadcast. The one step that needs doing is the first answer -
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] means "show my dialog", and the
 * intent to do that comes back in the extras.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent) ?: return
                // Started from a receiver, so it needs its own task.
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }

            PackageInstaller.STATUS_SUCCESS -> log(context, "Update installed")

            // Everything else is the user declining or Android refusing, and
            // both are ordinary: the app carries on as the version it is.
            else -> log(context, "Update not installed" + if (message.isNullOrBlank()) "" else ": $message")
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun log(context: Context, message: String) {
        runCatching { ZetaForgeApp.runtime(context).logger.info(SOURCE, message = message) }
    }

    companion object {
        private const val SOURCE = "Update"
        const val ACTION = "com.zetaforge.app.action.UPDATE_INSTALL_STATUS"

        /** The callback the session commits with. */
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
