package app.orchard.tv.wallpaper

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/** Lets the installed Zee build read only Aerial's current saved Home background. */
class CurrentWallpaperProvider : ContentProvider() {
    companion object {
        private const val ZEE_PACKAGE = "cn.stb.livetest"
        // Public certificate digest of the existing Zee installation's APK.
        private val zeeCertificate = "6dd38259c2432e2fcc126e2addccd93cc7520fccc68f17ba50546c3ae9c9b420"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val app = verifiedCaller()
        require(isCurrent(uri)) { "Unknown wallpaper URI" }
        return MatrixCursor(arrayOf("revision"), 1).apply {
            WallpaperSnapshots.current.revision(app)?.let { addRow(arrayOf(it)) }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = verifiedCaller()
        if (!isCurrent(uri) || mode != "r") throw FileNotFoundException("Only the current wallpaper can be read")
        val revision = uri.getQueryParameter("revision") ?: throw FileNotFoundException("Wallpaper revision required")
        return WallpaperSnapshots.current.open(app, revision)
    }

    override fun getType(uri: Uri): String? = if (isCurrent(uri)) "image/jpeg" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun isCurrent(uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority == "${context?.packageName}.wallpaper" && uri.path == "/current"

    private fun verifiedCaller(): Context {
        val app = checkNotNull(context)
        // ContentProvider verifies that callingPackage belongs to the Binder caller.
        if (Build.VERSION.SDK_INT < 28 || callingPackage != ZEE_PACKAGE ||
            !app.packageManager.hasSigningCertificate(ZEE_PACKAGE, zeeCertificate, PackageManager.CERT_INPUT_SHA256)
        ) throw SecurityException("Wallpaper access requires the signed Zee app")
        return app
    }
}
