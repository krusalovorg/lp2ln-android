package lp2ln_android.krusalov.org.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Bounded decoding avoids allocating the original camera-sized bitmap. */
internal object ImagePreview {
    const val MAX_THUMBNAIL_BYTES = 24 * 1024

    fun decode(file: File, maxEdge: Int, thumbnail: Boolean = false): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Формат изображения не поддерживается" }
        if (thumbnail) check(bounds.outWidth <= 256 && bounds.outHeight <= 256) { "Неверный размер миниатюры" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: error("Не удалось открыть изображение")
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(decoded.width, decoded.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1), true).also { if (it !== decoded) decoded.recycle() }
            else decoded
        val orientation = runCatching { ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        return if (!matrix.isIdentity) Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
            .also { if (it !== scaled) scaled.recycle() } else scaled
    }

    /** Optional sidecar: non-image files are imported normally. No EXIF is copied. */
    fun createThumbnail(source: File) {
        val bitmap = runCatching { decode(source, 256) }.getOrNull() ?: return
        val sidecar = File(source.absolutePath + ".preview")
        try {
            for (quality in listOf(72, 55, 38, 24)) {
                val bytes = ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                    output.toByteArray()
                }
                if (bytes.size <= MAX_THUMBNAIL_BYTES) {
                    sidecar.writeBytes(bytes)
                    return
                }
            }
        } finally { bitmap.recycle() }
    }
}

data class ImageViewState(
    val fileId: String = "", val thumbnail: Bitmap? = null, val image: Bitmap? = null,
    val loading: Boolean = false, val error: String? = null,
)
