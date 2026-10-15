package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.types.Qwdtt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QwdttPingTest {
    @Test fun vkPreparationDoesNotConsumeTheShortNetworkTimeout() = runBlocking {
        val root=Files.createTempDirectory("native-ping-auth").toFile()
        val input=PipedInputStream();val output=PipedOutputStream(input)
        try {
            File(root,CarrierKind.QWDTT.executable).apply { writeText("fixture");setExecutable(true) }
            val result=async {
                QwdttPing.execute(root,root,"{}",timeoutMs=1) {
                    object:Process() {
                        override fun getOutputStream()=ByteArrayOutputStream()
                        override fun getInputStream()=input
                        override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
                        override fun waitFor()=0
                        override fun exitValue():Int=throw IllegalThreadStateException()
                        override fun destroy() { output.close() }
                    }
                }
            }
            kotlinx.coroutines.delay(150)
            output.write("PING_RESULT|37\n".toByteArray());output.flush()
            assertEquals(37,result.await())
            assertEquals(110_000L,QwdttPing.processBudgetMs(20_000,1))
            assertEquals(380_000L,QwdttPing.processBudgetMs(20_000,4))
        } finally { output.close();input.close();root.deleteRecursively() }
    }

    @Test fun nativePingUsesDtlsPortAndNeverMutatesTheVpnProfile() {
        val profile=Qwdtt().apply { server="192.0.2.1";serverPort=56010;rawPort=56013
            password="fixture-secret";hashes=listOf("fixture-hash-123456789") }
        val before=profile.exportToJson().toCompact()
        val config=JsonInput.parseObject(QwdttPing.configuration(profile))
        assertEquals("192.0.2.1:56010",config.string("peer"))
        assertEquals("wg",config.string("mode"))
        assertTrue(config.bool("probe_only"))
        assertFalse(config.bool("interactive_captcha"))
        assertEquals(before,profile.exportToJson().toCompact())
        assertEquals(55,QwdttPing.parseResult("PING_RESULT|55"))
        assertEquals(1,QwdttPing.parseResult("PING_RESULT|0"))
        assertNull(QwdttPing.parseResult("PING_RESULT|-1"))
        assertNull(QwdttPing.parseResult("PING_ERROR|private-secret"))
    }

    @Test fun commandKeepsSecretsInPrivateFileAndRemovesItAfterResult() = runBlocking {
        val root=Files.createTempDirectory("native-ping").toFile()
        try {
            File(root,CarrierKind.QWDTT.executable).apply { writeText("fixture");setExecutable(true) }
            var destroyed=false
            val result=QwdttPing.execute(root,root,"""{"password":"fixture-secret"}""") { builder ->
                assertEquals("-ping-only",builder.command().last())
                assertFalse(builder.command().any { it.contains("fixture-secret") })
                assertTrue(File(builder.command()[2]).readText().contains("fixture-secret"))
                object:Process() {
                    override fun getOutputStream()=ByteArrayOutputStream()
                    override fun getInputStream()=ByteArrayInputStream("PING_RESULT|42\n".toByteArray())
                    override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
                    override fun waitFor()=0
                    override fun exitValue()=0
                    override fun destroy() { destroyed=true }
                }
            }
            assertEquals(42,result)
            assertTrue(destroyed)
            assertEquals(1,root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationKillsOnlyTheOwnedProbeAndRemovesItsPrivateConfig() = runBlocking {
        val root=Files.createTempDirectory("native-ping-stop").toFile()
        val input=PipedInputStream();val output=PipedOutputStream(input)
        try {
            File(root,CarrierKind.QWDTT.executable).apply { writeText("fixture");setExecutable(true) }
            val launched=CompletableDeferred<Unit>()
            var destroyed=false
            val job=async {
                QwdttPing.execute(root,root,"{}") {
                    launched.complete(Unit)
                    object:Process() {
                        override fun getOutputStream()=ByteArrayOutputStream()
                        override fun getInputStream()=input
                        override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
                        override fun waitFor()=0
                        override fun exitValue():Int=throw IllegalThreadStateException()
                        override fun destroy() { destroyed=true;output.close() }
                    }
                }
            }
            launched.await();job.cancelAndJoin()
            assertTrue(destroyed)
            assertEquals(1,root.listFiles()!!.size)
        } finally { output.close();input.close();root.deleteRecursively() }
    }
}
