package ir.hamyareqari.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * ذخیرهٔ خروجی‌ها: تصویر تحلیل، گزارش JSON و صدای ضبط‌شده.
 *
 * اندروید ۱۰+ : MediaStore با RELATIVE_PATH (بدون هیچ مجوزی)
 * اندروید ۹ و پایین‌تر : پوشهٔ عمومی + مجوز نوشتن (که در MainActivity گرفته می‌شود)
 */
object Persistence {

    private const val APP_DIR = "همیار قاری"

    class Saved(val uri: Uri?, val displayPath: String, val name: String)

    private const val KIND_IMAGE = 0
    private const val KIND_DOWNLOAD = 1

    fun saveBitmap(ctx: Context, bmp: Bitmap, name: String): Saved {
        val bytes = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        return saveBytes(ctx, bytes.toByteArray(), name, "image/png", KIND_IMAGE, Environment.DIRECTORY_PICTURES)
    }

    fun saveJson(ctx: Context, json: String, name: String): Saved {
        return saveBytes(ctx, json.toByteArray(Charsets.UTF_8), name, "application/json", KIND_DOWNLOAD, Environment.DIRECTORY_DOWNLOADS)
    }

    fun saveWav(ctx: Context, pcm: ShortArray, sampleRate: Int, name: String): Saved {
        val bytes = ir.hamyareqari.dsp.Wav.encode16Pcm(pcm, sampleRate)
        return saveBytes(ctx, bytes, name, "audio/wav", KIND_DOWNLOAD, Environment.DIRECTORY_DOWNLOADS)
    }

    /** مجوز نوشتن فقط برای اندروید ۹ و پایین‌تر لازم است */
    fun needsWritePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    private fun saveBytes(
        ctx: Context,
        bytes: ByteArray,
        name: String,
        mime: String,
        kind: Int,
        legacyDir: String
    ): Saved {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // این ثابت‌ها فقط از اندروید ۱۰ وجود دارند، پس فقط داخل همین شرط خوانده می‌شوند
            val collection: Uri =
                if (kind == KIND_IMAGE) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$legacyDir/$APP_DIR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(collection, values)
            if (uri != null) {
                try {
                    val out: OutputStream? = ctx.contentResolver.openOutputStream(uri)
                    if (out != null) {
                        out.use { it.write(bytes) }
                        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        ctx.contentResolver.update(uri, done, null, null)
                        return Saved(uri, "$legacyDir/$APP_DIR/$name", name)
                    }
                } catch (e: Exception) {
                    try { ctx.contentResolver.delete(uri, null, null) } catch (e2: Exception) { }
                }
            }
        }
        // مسیر جایگزین (اندروید ۹ و پایین‌تر یا اگر MediaStore نشد)
        return try {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(legacyDir), APP_DIR)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            FileOutputStream(f).use { it.write(bytes) }
            Saved(null, f.absolutePath, name)
        } catch (e: Exception) {
            // آخرین راه: حافظهٔ خصوصی اپ (همیشه کار می‌کند)
            val dir = File(ctx.getExternalFilesDir(null), APP_DIR)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            FileOutputStream(f).use { it.write(bytes) }
            Saved(null, f.absolutePath, name)
        }
    }

    fun share(ctx: Context, saved: Saved) {
        try {
            val uri = saved.uri
            if (uri != null) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = ctx.contentResolver.getType(uri) ?: "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(send, "ارسال «${saved.name}»"))
            } else {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_TEXT, saved.displayPath)
                }
                ctx.startActivity(Intent.createChooser(send, "مسیر فایل"))
            }
        } catch (e: Exception) {
            // اگر اشتراک‌گذاری ممکن نبود، مسیر در پیام نمایش داده شده است
        }
    }

    fun safeName(base: String, ext: String): String {
        val cleaned = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "تحلیل" }
        val short = if (cleaned.length > 40) cleaned.substring(0, 40) else cleaned
        return "$short.$ext"
    }
}
