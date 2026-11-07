package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File
import java.io.FileNotFoundException
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Shares the same full-size JPEG that Home keeps for its next start. */
internal class WallpaperSnapshots {
    companion object {
        val current = WallpaperSnapshots()
    }

    private var generation = 0L
    private var loaded = false
    private var revision: String? = null

    @Synchronized
    fun nextGeneration(): Long = ++generation

    @Synchronized
    fun revision(context: Context): String? {
        if (context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
                .getString("selected", null) == Wallpapers.AUTO
        ) return null
        if (!loaded) {
            val directory = File(context.filesDir, "backdrop")
            val home = File(directory, "home.jpg")
            // The pre-existing cache writes its key only after the image pair is complete.
            revision = if (File(directory, "key").isFile && home.length() > 0) digest(home) else null
            loaded = true
        }
        return revision
    }

    @Synchronized
    fun open(context: Context, requested: String): ParcelFileDescriptor {
        if (revision(context) != requested) throw FileNotFoundException("Wallpaper revision changed")
        // Opening under the same lock as the rename keeps this descriptor on the requested image.
        return ParcelFileDescriptor.open(File(context.filesDir, "backdrop/home.jpg"), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    fun hide(context: Context) {
        val changed = synchronized(this) {
            generation++
            val wasVisible = !loaded || revision != null
            loaded = true
            revision = null
            wasVisible
        }
        if (changed) notifyChanged(context)
    }

    fun save(context: Context, directory: File, request: Long, key: String, home: Bitmap, frost: Bitmap): Boolean {
        // Wallpapers serializes these writes. Reuse the staging path after an interrupted save.
        val pending = File(directory, "home.jpg.part")
        var changed = false
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(pending.outputStream(), hash).use {
                check(home.compress(Bitmap.CompressFormat.JPEG, 95, it))
            }
            val nextRevision = hash.digest().toHex()
            synchronized(this) {
                if (request != generation) return false
                val keyFile = File(directory, "key")
                // Write the key last so Home cannot reload an incomplete cache.
                if (keyFile.exists()) check(keyFile.delete())
                File(directory, "frost.png").outputStream().use {
                    check(frost.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                Os.rename(pending.path, File(directory, "home.jpg").path)
                changed = !loaded || revision != nextRevision
                revision = nextRevision
                loaded = true
                keyFile.writeText(key)
            }
            return true
        } finally {
            pending.delete()
            if (changed) notifyChanged(context)
        }
    }

    private fun notifyChanged(context: Context) {
        val uri = Uri.parse("content://${context.packageName}.wallpaper/current")
        @Suppress("DEPRECATION")
        context.contentResolver.notifyChange(uri, null, false)
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
