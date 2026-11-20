package app.orchard.tv.guard

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import app.orchard.tv.MainActivity
import app.orchard.tv.system.DiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals("正在启动直播，等待约30秒", hint.text.toString())
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
