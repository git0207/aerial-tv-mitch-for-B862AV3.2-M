package app.orchard.tv.wallpaper

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [WallpaperSnapshotsTest.HostRename::class])
class WallpaperSnapshotsTest {
    private val snapshots = WallpaperSnapshots()
    private val app = RuntimeEnvironment.getApplication()
    private val directory = File(app.filesDir, "backdrop").apply { mkdirs() }
    private val uri = Uri.parse("content://${app.packageName}.wallpaper/current")

    @Test
    fun coldReadReusesExistingBackdrop() {
        val bytes = byteArrayOf(11, 22, 33)
        File(directory, "home.jpg").writeBytes(bytes)
        File(directory, "key").writeText("cached wallpaper")
        val revision = requireNotNull(snapshots.revision(app))
        assertEquals(hash(bytes), revision)
        assertEquals(bytes.toList(), read(snapshots.open(app, revision)).toList())
        app.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).edit().putString("selected", Wallpapers.AUTO).apply()
        assertNull(snapshots.revision(app))
        assertThrows(FileNotFoundException::class.java) { snapshots.open(app, revision) }
    }

    @Test
    fun publishesNewBytesBeforeNotificationAndRejectsStaleReads() {
        val notifications = mutableListOf<String?>()
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                notifications += snapshots.revision(app)
            }
        }
        app.contentResolver.registerContentObserver(uri, false, observer)
        try {
            val first = publish(96)
            val staleRequest = snapshots.nextGeneration()
            val second = publish(192)
            assertNotEquals(first, second)
            assertEquals(listOf(first, second), notifications)
            assertEquals(second, hash(read(snapshots.open(app, second))))
            assertThrows(FileNotFoundException::class.java) { snapshots.open(app, first) }
            assertEquals(false, save(staleRequest, 32))
            assertEquals(second, snapshots.revision(app))
            snapshots.hide(app)
            assertNull(snapshots.revision(app))
            assertEquals(listOf(first, second, null), notifications)
        } finally {
            app.contentResolver.unregisterContentObserver(observer)
        }
    }

    @Test
    fun openDescriptorKeepsReadingItsOriginalImageAfterReplacement() {
        // Windows host descriptors do not have Android's POSIX unlink semantics.
        assumeFalse(File.separatorChar == '\\')
        val first = publish(96)
        val file = snapshots.open(app, first)
        ParcelFileDescriptor.AutoCloseInputStream(file).use { input ->
            assertNotEquals(first, publish(192))
            assertEquals(first, hash(input.readBytes()))
        }
    }

    @Test
    fun providerRejectsOtherAppsBeforeReturningMetadataOrFiles() {
        val info = ProviderInfo().apply {
            authority = uri.authority
            applicationInfo = app.applicationInfo
            exported = true
        }
        val provider = Robolectric.buildContentProvider(CurrentWallpaperProvider::class.java).create(info).get()
        assertNotNull(provider.getType(uri))
        assertThrows(SecurityException::class.java) { provider.query(uri, null, null, null, null) }
        assertThrows(SecurityException::class.java) { provider.openFile(uri, "r") }
    }

    private fun publish(width: Int): String {
        assertTrue(save(snapshots.nextGeneration(), width))
        return requireNotNull(snapshots.revision(app))
    }

    private fun save(request: Long, width: Int): Boolean {
        val home = Bitmap.createBitmap(width, width / 2, Bitmap.Config.ARGB_8888)
        val frost = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
        return snapshots.save(app, directory, request, "wallpaper-$width", home, frost)
    }

    private fun read(file: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(file).use { it.readBytes() }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Implements(Os::class)
    class HostRename {
        companion object {
            @JvmStatic
            @Implementation
            protected fun rename(source: String, destination: String) {
                Files.move(File(source).toPath(), File(destination).toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }
}
