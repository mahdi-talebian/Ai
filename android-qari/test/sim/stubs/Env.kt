package android.content

import java.io.File

class Context {
    val assets = Any()
    val cacheDir: File = File(System.getProperty("sim.cache", "build-test/cache")).apply { mkdirs() }
    val filesDir: File = File(System.getProperty("sim.files", "build-test/files")).apply { mkdirs() }
}
