package app.qthrone.mobile.bg.carrier

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CarrierProcessTest {
    @Test fun failedStartUsesPrivateFileAndRemovesIt() = runBlocking {
        val root = Files.createTempDirectory("carrier-test").toFile()
        try {
            val executable = File(root, CarrierKind.QWDTT.executable)
            executable.writeText("test fixture")
            assertTrue(executable.setExecutable(true))
            val configs = File(root, "private")
            val privateJson = "{\"password\":\"test-fixture-secret\"}"
            var launched = false
            var destroyed = false
            val error = runCatching {
                CarrierProcess.start(CarrierKind.QWDTT, root, configs, 24, privateJson, 12345) { builder ->
                    launched = true
                    assertEquals(3, builder.command().size)
                    assertEquals("-config", builder.command()[1])
                    assertFalse(builder.command().any { it.contains("test-fixture-secret") })
                    assertTrue(File(builder.command()[2]).readText() == privateJson)
                    object : Process() {
                        override fun getOutputStream() = ByteArrayOutputStream()
                        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                        override fun waitFor() = 1
                        override fun exitValue() = 1
                        override fun destroy() { destroyed = true }
                    }
                }
            }.exceptionOrNull()
            assertTrue(launched)
            assertTrue(destroyed)
            assertTrue(error is IllegalStateException)
            assertFalse(error?.message.orEmpty().contains("test-fixture-secret"))
            assertTrue(configs.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun csqttRejectsUnsupportedAndroidBeforeLaunching() = runBlocking {
        var launched = false
        val error = runCatching {
            CarrierProcess.start(CarrierKind.CSQTT, File("missing"), File("missing"), 24, "{}", 12345) {
                launched = true
                error("must not launch")
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertFalse(launched)
    }
}
