package app.orchard.tv.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import app.orchard.tv.MainActivity
import app.orchard.tv.system.DiagLog
import kotlin.concurrent.thread

/**
 * After a real reboot: restore Home Guard, and if the firmware still hands Home to its own
 * launcher, open Aerial. Starting an activity from a receiver needs the "display over other apps"
 * permission on Android 10+, which tools/install.sh grants.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the boot broadcasts only; anything else could use it to pull Aerial forward.
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        // Off the main thread: putting the guard back is settings, AccountManager and sync calls
        // into system_server, and the broadcast's timeout counts every one. XGIMI Z6X (Android 8)
        // sends LOCKED_BOOT_COMPLETED again when the screen locks, while the screensaver's film
        // decodes and the system answers slowly; the receiver ran out of time there and the TV
        // reported Aerial as not responding (issue #614).
        val pending = goAsync()
        val start = SystemClock.elapsedRealtime()
        thread(name = "AerialBoot") {
            try {
                onBoot(context, action)
            } finally {
                pending.finish()
                // In the diagnostics: how long the slow side of #614 takes on the TV at hand.
                DiagLog.i("${action.substringAfterLast('.')} handled in ${SystemClock.elapsedRealtime() - start} ms")
            }
        }
    }

    internal fun onBoot(context: Context, action: String) {
        GuardSetup.ensureEnabled(context)
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            synchronized(Companion) {
                if (bootStage == BootStage.WAITING && canOpenEarly(context)) {
                    bootStage = BootStage.EARLY_HOME
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) BootCover.show(context)
                    if (!openHome(context, "early boot")) {
                        bootStage = BootStage.WAITING
                        BootCover.cancelEarlyStart()
                    }
                }
            }
            return
        }
        val openedEarly = synchronized(Companion) {
            val early = bootStage == BootStage.EARLY_HOME
            bootStage = BootStage.COMPLETE
            early
        }
        GuardSetup.rescanIfMissing(context)
        if (openedEarly) {
            // Bring Home back if the firmware covered it and let the usual boot target run now.
            // Home may already be in front, in which case the existing activity receives the intent.
            openHome(context, "boot completed after early Home", finishEarlyBoot = true)
            return
        }
        // Also when Aerial is the default Home: some firmwares start their own launcher at boot
        // regardless (Skyworth/Coolita 8R145, issue #43).
        // A guard that is already running looks at what is in front before taking Home: by the time
        // this arrives, an HDMI-CEC source may already have switched the TV to its input. It still
        // has to look: on Xiaomi MiTV-MTEQ0 (Android 10) it is bound ~20 s before BOOT_COMPLETED,
        // never sees PatchWall's windows, and the take-over at bind time did not stick (issue #154).
        if (HomeGuardService.onBootCompleted()) return
        openHome(context, "boot completed")
    }

    private fun canOpenEarly(context: Context): Boolean =
        // On the Sichuan Mobile ZTE B862AV3.2-M (Android 9), this broadcast arrived after
        // unlock, about four seconds before the firmware requested its stock launcher.
        Build.VERSION.SDK_INT == Build.VERSION_CODES.P &&
            Build.MODEL == "B862AV3.2-M" && Build.DEVICE == "pineappleCMCC" &&
            SystemClock.elapsedRealtime() < 60_000 &&
            GuardSetup.isUserUnlocked(context) &&
            context.getSystemService<PowerManager>()?.isInteractive == true

    private fun openHome(context: Context, reason: String, finishEarlyBoot: Boolean = false): Boolean = runCatching {
        val intent = MainActivity.bringBackIntent(context)
        if (finishEarlyBoot) intent.putExtra(MainActivity.EXTRA_WOKE, true)
        context.startActivity(intent)
        DiagLog.i("$reason: requested Aerial Home")
    }.onFailure { DiagLog.i("$reason: could not open Aerial Home (${it.javaClass.simpleName})") }
        .isSuccess

    private enum class BootStage { WAITING, EARLY_HOME, COMPLETE }

    companion object {
        @Volatile
        private var bootStage = BootStage.WAITING

        internal val deferBootTarget: Boolean
            get() = bootStage == BootStage.EARLY_HOME

        internal val finishedBoot: Boolean
            get() = bootStage == BootStage.COMPLETE
    }
}
