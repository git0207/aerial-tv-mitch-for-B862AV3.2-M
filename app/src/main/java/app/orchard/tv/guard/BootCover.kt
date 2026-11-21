package app.orchard.tv.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.getSystemService
import app.orchard.tv.R
import app.orchard.tv.system.DiagLog
import app.orchard.tv.wallpaper.Images
import app.orchard.tv.wallpaper.WallpaperSnapshots
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Masks the firmware's launcher while it starts underneath Aerial. State lives on the main thread. */
internal object BootCover {
    private const val MAX_VISIBLE_MS = 20_000L
    private val main = Handler(Looper.getMainLooper())
    private var view: FrameLayout? = null
    private var wallpaperUpdates: Job? = null
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
    fun show(context: Context, blurDispatcher: CoroutineDispatcher = Dispatchers.Default) {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            DiagLog.i("boot cover: overlay permission unavailable")
            return
        }
        val wallpaper = runCatching {
            WallpaperSnapshots.cachedHomeBackdrop(app)?.let {
                BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        }.getOrNull()
        // The boot receiver already runs off the main thread. Prepare the first frame together.
        val initialBlur = wallpaper?.let(::blurWallpaper)
        main.post {
            if (view != null) return@post
            val windows = app.getSystemService<WindowManager>() ?: return@post
            val backdrop = ImageView(app).apply {
                setBackgroundColor(Color.rgb(18, 18, 20))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(if (initialBlur != null) wallpaper else null)
            }
            val hint = liveTvHint(app, backdrop)
            val hintBackground = hint.background as HintBackground
            hintBackground.setBlurredWallpaper(initialBlur)
            val cover = FrameLayout(app).apply {
                addView(backdrop, FrameLayout.LayoutParams(-1, -1))
                addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
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
                wallpaperUpdates = CoroutineScope(Dispatchers.Main.immediate).launch {
                    snapshotFlow { Wallpapers.backdrop to Wallpapers.selectedId }
                        // Home has no in-memory picture until it loads its cache.
                        .filter { (image, selection) -> image != null || selection == Wallpapers.AUTO }
                        .map { (image, _) -> image?.asAndroidBitmap() }
                        .distinctUntilChanged()
                        .collectLatest { picture ->
                            val blurred = picture?.let {
                                withContext(blurDispatcher) { blurWallpaper(it) } ?: return@collectLatest
                            }
                            // Keep drawing the previous pair until both layers of the new picture are ready.
                            backdrop.setImageBitmap(picture)
                            hintBackground.setBlurredWallpaper(blurred)
                        }
                }
                DiagLog.i("boot cover: attached")
            }.onFailure {
                hide("attachment failed")
                DiagLog.e("boot cover: could not show", it)
            }
        }
    }

    private fun liveTvHint(context: Context, backdrop: ImageView): TextView {
        // Match the Zee live TV pause card's dimensions on its 1280x720 design canvas.
        val metrics = context.resources.displayMetrics
        val scale = minOf(metrics.widthPixels / 1280f, metrics.heightPixels / 720f)
        val horizontal = (36 * scale).roundToInt()
        val vertical = (28 * scale).roundToInt()
        return TextView(context).apply {
            setText(R.string.boot_cover_live_tv_hint)
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (32 * scale).roundToInt().toFloat())
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(horizontal, vertical, horizontal, vertical)
            setShadowLayer((3 * scale).roundToInt().toFloat(), 0f, scale.roundToInt().toFloat(), 0xCC000000.toInt())
            background = HintBackground(
                backdrop, this, (12 * scale).roundToInt().toFloat(), scale.roundToInt().coerceAtLeast(1),
            )
            isFocusable = false
        }
    }

    private fun blurWallpaper(wallpaper: Bitmap): Bitmap? = runCatching {
        // At twice the sampling resolution, the same kernel covers half the screen distance.
        val width = 320
        val height = 180
        val sample = Bitmap.createScaledBitmap(wallpaper, width, height, true)
        val pixels = IntArray(width * height)
        sample.getPixels(pixels, 0, width, 0, 0, width, height)
        Images.boxBlur(pixels, width, height, 2)
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }.onFailure { DiagLog.e("boot cover: could not blur wallpaper", it) }.getOrNull()

    private class HintBackground(
        private val backdrop: ImageView,
        private val hint: TextView,
        private val radius: Float,
        strokeWidth: Int,
    ) : GradientDrawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()
        private var blurred: Bitmap? = null

        init {
            setColor(0x26000000)
            cornerRadius = radius
            setStroke(strokeWidth, 0x26FFFFFF)
        }

        fun setBlurredWallpaper(bitmap: Bitmap?) {
            if (blurred === bitmap) return
            blurred = bitmap
            paint.shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val sample = blurred
            val source = backdrop.drawable
            if (sample != null && source != null) {
                // Use the same center crop as the wallpaper, translated into the hint's bounds.
                matrix.setScale(source.bounds.width() / sample.width.toFloat(), source.bounds.height() / sample.height.toFloat())
                matrix.postConcat(backdrop.imageMatrix)
                matrix.postTranslate((backdrop.left - hint.left).toFloat(), (backdrop.top - hint.top).toFloat())
                paint.shader?.setLocalMatrix(matrix)
                canvas.drawRoundRect(
                    bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
                    radius, radius, paint,
                )
            }
            super.draw(canvas)
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
        wallpaperUpdates?.cancel()
        wallpaperUpdates = null
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
