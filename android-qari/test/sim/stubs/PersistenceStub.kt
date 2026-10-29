package ir.hamyareqari.app

import android.content.Context
import java.io.File

/** جایگزین حداقلی برای تست دسکتاپ */
object Persistence {
    class Saved(val path: String, val displayPath: String)
    fun saveVideo(ctx: Context, file: File, name: String): Saved {
        val out = File(ctx.filesDir, name)
        file.copyTo(out, overwrite = true)
        return Saved(out.absolutePath, out.absolutePath)
    }
}
