package app.qthrone.mobile.bg.carrier

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CarrierProcessTest {
    @Test fun qwdttAndCsqttExposeServerAuthRefusalInsteadOfStartupTimeout() = runBlocking {
        val root=Files.createTempDirectory("carrier-auth-refusal").toFile()
        try {
            val native=File(root,"native").apply { mkdir() }
            File(native,"libqwdtt.so").apply { writeText("fixture");setExecutable(true) }
            File(native,"libcsqtt_transport.so").apply { writeText("fixture");setExecutable(true) }
            for(kind in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT)) {
                val directory=File(root,kind.name).apply { mkdir() }
                val error=runCatching {
                    CarrierProcess.start(kind,native,directory,35,"{}",12345,CarrierBridgeCredentials.fresh(),1000) {
                        object:Process() {
                            override fun getOutputStream()=ByteArrayOutputStream()
                            override fun getInputStream()=ByteArrayInputStream("FATAL_AUTH: fixture-secret\n".toByteArray())
                            override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
                            override fun waitFor()=0
                            override fun exitValue()=0
                            override fun destroy() {}
                        }
                    }
                }.exceptionOrNull()
                assertEquals(NativeSessionPolicy.startupFailure("FATAL_AUTH:"),error?.message)
                assertFalse(error?.message.orEmpty().contains("fixture-secret"))
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
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
            val control=ByteArrayOutputStream()
            val error = runCatching {
                CarrierProcess.start(CarrierKind.QWDTT, root, configs, 24, privateJson, 12345, CarrierBridgeCredentials.fresh()) { builder ->
                    launched = true
                    assertEquals(3, builder.command().size)
                    assertEquals("-config", builder.command()[1])
                    assertFalse(builder.command().any { it.contains("test-fixture-secret") })
                    assertTrue(File(builder.command()[2]).readText() == privateJson)
                    object : Process() {
                        override fun getOutputStream() = control
                        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                        override fun waitFor() = 1
                        override fun exitValue() = 1
                        override fun destroy() { destroyed = true }
                    }
                }
            }.exceptionOrNull()
            assertTrue(launched)
            // An already exited child needs cleanup, not another termination signal.
            assertFalse(destroyed)
            assertEquals("STOP\n",control.toString("UTF-8"))
            assertTrue(error is IllegalStateException)
            assertFalse(error?.message.orEmpty().contains("test-fixture-secret"))
            assertTrue(configs.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun csqttRejectsUnsupportedAndroidBeforeLaunching() = runBlocking {
        var launched = false
        val error = runCatching {
            CarrierProcess.start(CarrierKind.CSQTT, File("missing"), File("missing"), 24, "{}", 12345, CarrierBridgeCredentials.fresh()) {
                launched = true
                error("must not launch")
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertFalse(launched)
    }

    @Test fun readinessRejectsAnUnauthenticatedLocalProxy() {
        probeServer { listener ->
            listener.accept().use { socket ->
                socket.soTimeout = 2000
                DataInputStream(socket.getInputStream()).readFully(ByteArray(3))
                socket.getOutputStream().write(byteArrayOf(5, 0))
            }
        }.let { assertFalse(it) }
    }

    @Test fun readinessRequiresSuccessfulPasswordAuthentication() {
        for (accepted in listOf(true, false)) {
            val ready = probeServer { listener ->
                listener.accept().use { socket ->
                    socket.soTimeout = 2000
                    DataInputStream(socket.getInputStream()).readFully(ByteArray(3))
                    socket.getOutputStream().write(byteArrayOf(5, -1))
                }
                listener.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = DataInputStream(socket.getInputStream())
                    val greeting = ByteArray(3).also { input.readFully(it) }
                    assertArrayEquals(byteArrayOf(5, 1, 2), greeting)
                    socket.getOutputStream().write(byteArrayOf(5, 2))
                    assertEquals(1, input.readUnsignedByte())
                    val username = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    val password = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    assertEquals("fixture-user", String(username, Charsets.UTF_8))
                    assertEquals("fixture-password", String(password, Charsets.UTF_8))
                    socket.getOutputStream().write(byteArrayOf(1, if (accepted) 0 else 1))
                }
            }
            assertEquals(accepted, ready)
        }
    }

    private fun probeServer(serve: (ServerSocket) -> Unit): Boolean {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 2000
                val server = executor.submit { serve(listener) }
                val ready = CarrierProcess.socksReady(listener.localPort,
                    CarrierBridgeCredentials("fixture-user", "fixture-password"))
                server.get(3, TimeUnit.SECONDS)
                return ready
            }
        } finally { executor.shutdownNow() }
    }
}
