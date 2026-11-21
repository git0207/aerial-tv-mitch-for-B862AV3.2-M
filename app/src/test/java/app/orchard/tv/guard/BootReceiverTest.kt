package app.orchard.tv.guard

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.orchard.tv.MainActivity
import app.orchard.tv.system.DiagLog
import app.orchard.tv.wallpaper.WallpaperSnapshots
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.io.File
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BootReceiverTest {
    private val app = RuntimeEnvironment.getApplication()
    private val receiver = BootReceiver()
    private val user = shadowOf(app.getSystemService(Context.USER_SERVICE) as UserManager)
    private val launcher = ComponentName(app, MainActivity::class.java)
    private val started get() = shadowOf(app).nextStartedActivity

    @Before
    @Suppress("DEPRECATION") // ShadowPowerManager models the state read by isInteractive on API 28.
    fun setUp() {
        BootCover.cancelEarlyStart()
        shadowOf(Looper.getMainLooper()).idle()
        val stage = ReflectionHelpers.getStaticField<Enum<*>>(BootReceiver::class.java, "bootStage")
        val initialStage = requireNotNull(stage.declaringJavaClass.enumConstants).single { it.name == "WAITING" }
        ReflectionHelpers.setStaticField(BootReceiver::class.java, "bootStage", initialStage)
        ShadowBuild.setModel("B862AV3.2-M")
        ShadowBuild.setDevice("pineappleCMCC")
        user.setUserUnlocked(true)
        shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun keepsTheCoverThroughTheBootHandoffAndReleasesOnTimeout() {
        ShadowSettings.setCanDrawOverlays(true)
        val main = shadowOf(Looper.getMainLooper())
        receiver.onBoot(app, Intent.ACTION_LOCKED_BOOT_COMPLETED)
        main.idle()
        val cover = ReflectionHelpers.getStaticField<FrameLayout>(BootCover::class.java, "view")
        val hint = cover.getChildAt(1) as TextView
        assertEquals("正在启动直播\n等待约30秒", hint.text.toString())
        assertEquals(0x26000000, (hint.background as GradientDrawable).color?.defaultColor)
        assertEquals(Gravity.CENTER, (hint.layoutParams as FrameLayout.LayoutParams).gravity)
        assertFalse(hint.isFocusable)
        receiver.onBoot(app, Intent.ACTION_BOOT_COMPLETED)
        var launches = 0
        val targetDeferred = BootCover.deferTarget { launches++ }
        assertTrue(DiagLog.recent(6).joinToString("\n"), targetDeferred)
        assertEquals(0, launches)

        main.idleFor(Duration.ofSeconds(20))
        assertEquals(1, launches)
        assertFalse(BootCover.deferTarget { launches++ })
    }

    @Test
    fun readsTheBakedHomePictureWithoutReusingTheLockedBootCopy() {
        val lockedCopy = requireNotNull(Wallpapers.lockedBootBackdrop(app))
        lockedCopy.writeBytes(byteArrayOf(1))
        assertNull(WallpaperSnapshots.cachedHomeBackdrop(app))

        val directory = File(app.filesDir, "backdrop").apply { mkdirs() }
        val home = File(directory, "home.jpg").apply { writeBytes(byteArrayOf(2)) }
        assertNull(WallpaperSnapshots.cachedHomeBackdrop(app))
        File(directory, "key").writeText("daily:20261120|0|1920x1080|100")
        assertEquals(home, WallpaperSnapshots.cachedHomeBackdrop(app))

        app.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
            .edit().putString("selected", Wallpapers.AUTO).apply()
        assertNull(WallpaperSnapshots.cachedHomeBackdrop(app))
    }

    @Test
    fun startsWithBlurAndKeepsItUntilTheNextWallpaperIsReady() {
        ShadowSettings.setCanDrawOverlays(true)
        val main = shadowOf(Looper.getMainLooper())
        val directory = File(app.filesDir, "backdrop").apply { mkdirs() }
        File(directory, "home.jpg").outputStream().use {
            val cached = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
            assertTrue(cached.compress(Bitmap.CompressFormat.JPEG, 95, it))
        }
        File(directory, "key").writeText("daily:20261120|0|1920x1080|103")
        val blurTasks = ArrayDeque<Runnable>()
        val blurDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                blurTasks.addLast(block)
            }
        }
        BootCover.show(app, blurDispatcher)
        main.idle()
        val cover = ReflectionHelpers.getStaticField<FrameLayout>(BootCover::class.java, "view")
        val background = cover.getChildAt(0) as ImageView
        val hint = cover.getChildAt(1)
        fun hintBlur() = ReflectionHelpers.getField<Bitmap?>(hint.background, "blurred")
        val state = ReflectionHelpers.getStaticField<MutableState<ImageBitmap?>>(Wallpapers::class.java, "backdrop\$delegate")
        val before = state.value
        try {
            val cached = (background.drawable as BitmapDrawable).bitmap
            val cachedBlur = hintBlur()
            assertNotNull(cachedBlur)
            val current = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
            state.value = current.asImageBitmap()
            Snapshot.sendApplyNotifications()
            main.idle()
            assertSame(cached, (background.drawable as BitmapDrawable).bitmap)
            assertSame(cachedBlur, hintBlur())

            blurTasks.removeFirst().run()
            main.idle()
            assertSame(current, (background.drawable as BitmapDrawable).bitmap)
            val currentBlur = hintBlur()
            assertNotNull(currentBlur)
            assertNotSame(cachedBlur, currentBlur)

            BootCover.cancelEarlyStart()
            main.idle()
            state.value = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).asImageBitmap()
            Snapshot.sendApplyNotifications()
            main.idle()
            assertSame(current, (background.drawable as BitmapDrawable).bitmap)
            assertSame(currentBlur, hintBlur())
        } finally {
            BootCover.cancelEarlyStart()
            state.value = before
            Snapshot.sendApplyNotifications()
            main.idle()
        }
    }

    @Test
    fun overlayDenialKeepsTheEarlyHomeAndNormalBootTarget() {
        ShadowSettings.setCanDrawOverlays(false)
        receiver.onBoot(app, Intent.ACTION_LOCKED_BOOT_COMPLETED)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(launcher, started?.component)
        assertFalse(BootCover.deferTarget { error("No cover without permission") })

        receiver.onBoot(app, Intent.ACTION_BOOT_COMPLETED)
        assertTrue(started?.getBooleanExtra(MainActivity.EXTRA_WOKE, false) == true)
    }

    @Test
    fun opensHomeEarlyAndDefersTheBootTargetUntilBootCompleted() {
        receiver.onBoot(app, Intent.ACTION_LOCKED_BOOT_COMPLETED)
        val early = started
        assertEquals(launcher, early?.component)
        assertFalse(early.getBooleanExtra(MainActivity.EXTRA_WOKE, false))
        assertTrue(BootReceiver.deferBootTarget)

        receiver.onBoot(app, Intent.ACTION_LOCKED_BOOT_COMPLETED)
        assertNull(started)

        receiver.onBoot(app, Intent.ACTION_BOOT_COMPLETED)
        val completed = started
        assertEquals(launcher, completed?.component)
        assertTrue(completed.getBooleanExtra(MainActivity.EXTRA_WOKE, false))
        assertFalse(BootReceiver.deferBootTarget)
    }

    @Test
    fun staysOnTheNormalBootPathWhileUserIsLocked() {
        user.setUserUnlocked(false)
        receiver.onBoot(app, Intent.ACTION_LOCKED_BOOT_COMPLETED)
        assertNull(started)
        assertFalse(BootReceiver.deferBootTarget)

        user.setUserUnlocked(true)
        receiver.onBoot(app, Intent.ACTION_BOOT_COMPLETED)
        assertEquals(launcher, started?.component)
    }

    @Test
    fun leavesTheEarlyBroadcastAloneOnOtherDevices() {
        ShadowBuild.setModel("Another TV")
        receiver.onBoot(app, Intent.ACTION_LOCKED_BOOT_COMPLETED)
        assertNull(started)
        assertFalse(BootReceiver.deferBootTarget)

        receiver.onBoot(app, Intent.ACTION_BOOT_COMPLETED)
        assertEquals(launcher, started?.component)
    }

    @Test
    @Suppress("DEPRECATION")
    fun doesNotWakeTheScreenOnTheEarlyBroadcast() {
        shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(false)
        receiver.onBoot(app, Intent.ACTION_LOCKED_BOOT_COMPLETED)
        assertNull(started)
        assertFalse(BootReceiver.deferBootTarget)
    }

    @Test
    fun normalBootStillOpensHomeIfTheEarlyStartIsRejected() {
        val rejectingContext = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                throw SecurityException("Early activity start rejected")
            }
        }
        receiver.onBoot(rejectingContext, Intent.ACTION_LOCKED_BOOT_COMPLETED)
        assertNull(started)
        assertFalse(BootReceiver.deferBootTarget)

        receiver.onBoot(app, Intent.ACTION_BOOT_COMPLETED)
        val completed = started
        assertEquals(launcher, completed?.component)
        assertFalse(completed.getBooleanExtra(MainActivity.EXTRA_WOKE, false))
    }
}
