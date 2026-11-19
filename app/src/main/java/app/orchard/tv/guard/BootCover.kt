package app.orchard.tv.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import app.orchard.tv.system.DiagLog
import app.orchard.tv.wallpaper.Wallpapers

/** Masks the firmware's launcher while it starts underneath Aerial. State lives on the main thread. */
internal object BootCover {
    private const val MAX_VISIBLE_MS = 20_000L
    private val main = Handler(Looper.getMainLooper())
    private var view: ImageView? = null
    private var manager: WindowManager? = null
    private var screenOffContext: Context? = null
    private var pendingTarget: (() -> Unit)? = null
    private var homeWindow: Window? = null
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null
    private val timeout = Runnable { hide("timeout", runTarget = true) }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = hide("screen off")
    }

    /** Queues the only window the cover needs, before starting the heavier Home activity. */
    @RequiresApi(Build.VERSION_CODES.O)
    fun show(context: Context) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            DiagLog.i("boot cover: overlay permission unavailable")
            return
        }
        val wallpaper = runCatching {
            Wallpapers.lockedBootBackdrop(app)?.takeIf { it.isFile }?.let {
                BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        }.getOrNull()
        main.post {
            if (view != null) return@post
            val windows = app.getSystemService<WindowManager>() ?: return@post
            val cover = ImageView(app).apply {
                setBackgroundColor(Color.rgb(18, 18, 20))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(wallpaper)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE,
            ).apply { title = "Aerial boot cover" }
            runCatching {
                windows.addView(cover, params)
                manager = windows
                view = cover
                main.postDelayed(timeout, MAX_VISIBLE_MS)
                // SCREEN_OFF is a protected system broadcast. This path runs on Android 9.
                app.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
                screenOffContext = app
                DiagLog.i("boot cover: attached")
            }.onFailure {
                hide("attachment failed")
                DiagLog.e("boot cover: could not show", it)
            }
        }
    }

    fun cancelEarlyStart() {
        main.post { hide("early start failed") }
    }

    fun deferTarget(block: () -> Unit): Boolean {
        if (view == null) return false
        pendingTarget = block
        return true
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onHomeFocused(window: Window) {
        if (view == null || !BootReceiver.finishedBoot || frameListener != null) return
        val decor = window.decorView
        val listener = Window.OnFrameMetricsAvailableListener { _, _, _ ->
            if (homeWindow === window && decor.hasWindowFocus()) {
                decor.postOnAnimation {
                    if (homeWindow === window && decor.hasWindowFocus()) hide("Home frame", runTarget = true)
                }
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, main)
        homeWindow = window
        frameListener = listener
        // If early Home still has focus, it may have already stopped drawing while idle.
        decor.invalidate()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun forgetHome(window: Window) {
        if (homeWindow !== window) return
        frameListener?.let { window.removeOnFrameMetricsAvailableListener(it) }
        homeWindow = null
        frameListener = null
    }

    private fun hide(reason: String, runTarget: Boolean = false) {
        val cover = view ?: return
        main.removeCallbacks(timeout)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) homeWindow?.let(::forgetHome)
        screenOffContext?.let { runCatching { it.unregisterReceiver(screenOff) } }
        screenOffContext = null
        runCatching { manager?.removeViewImmediate(cover) }
            .onFailure { DiagLog.i("boot cover: could not remove window (${it.javaClass.simpleName})") }
        manager = null
        view = null
        val target = pendingTarget
        pendingTarget = null
        DiagLog.i("boot cover: removed ($reason)")
        if (runTarget) target?.invoke()
    }
}
