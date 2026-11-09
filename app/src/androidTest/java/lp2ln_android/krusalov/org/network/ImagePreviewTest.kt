package lp2ln_android.krusalov.org.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagePreviewTest {
    @Test fun thumbnailIsBoundedRotatedAndDoesNotCopyExif() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val original = File.createTempFile("preview-test-", ".jpg", directory)
        val sidecar = File(original.absolutePath + ".preview")
        try {
            Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(android.graphics.Color.MAGENTA)
                original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
            }
            ExifInterface(original.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, "6")
                setAttribute(ExifInterface.TAG_MAKE, "Private camera information")
                saveAttributes()
            }
            ImagePreview.createThumbnail(original)
            assertTrue(sidecar.length() in 1..ImagePreview.MAX_THUMBNAIL_BYTES.toLong())
            val decoded = ImagePreview.decode(sidecar, 256, thumbnail = true)
            assertTrue(decoded.height <= 256 && decoded.width <= 256)
            assertTrue(decoded.height > decoded.width)
            decoded.recycle()
            assertNull(ExifInterface(sidecar.absolutePath).getAttribute(ExifInterface.TAG_MAKE))
            val full = ImagePreview.decode(original, 512)
            assertEquals(512, full.height)
            assertEquals(256, full.width)
            full.recycle()
        } finally { sidecar.delete(); original.delete() }
    }

    @Test fun ordinaryFilesDoNotGetThumbnailsAndOversizedThumbnailDimensionsAreRejected() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val source = File.createTempFile("preview-text-", ".bin", directory)
        try {
            source.writeText("A normal document")
            ImagePreview.createThumbnail(source)
            assertFalse(File(source.absolutePath + ".preview").exists())
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 50, it) }
            bitmap.recycle()
            assertThrows(IllegalStateException::class.java) { ImagePreview.decode(source, 256, thumbnail = true) }
        } finally { source.delete() }
    }
}
