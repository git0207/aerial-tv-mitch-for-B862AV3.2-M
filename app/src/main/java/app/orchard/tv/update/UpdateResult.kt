package app.orchard.tv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the result of reinstalling the running APK for dex compilation. */
class UpdateResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SelfCompileInstaller.onSessionResult(context, intent)
}
