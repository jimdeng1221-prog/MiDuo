package com.jake.duolauncher

import androidx.test.platform.app.InstrumentationRegistry
import moe.shizuku.manager.adb.*
import org.conscrypt.Conscrypt
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** Protocol fixtures use only loopback test servers, never the system adbd or a permission grant. */
class LocalAdbTransportTest {
    private fun identity(): AdbKey {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return AdbKey(PreferenceAdbKeyStore(context.getSharedPreferences("miduo_adb_protocol_fixture", 0)), "MiDuo-test")
    }
    private fun serverContext(key: AdbKey): SSLContext {
        fun field(name: String) = AdbKey::class.java.getDeclaredField(name).apply { isAccessible = true }.get(key)
        val privateKey = field("privateKey") as PrivateKey
        val cert = field("certificate") as X509Certificate
        val manager = object : X509ExtendedKeyManager() {
            override fun getPrivateKey(alias: String?) = privateKey
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = "fixture"
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "fixture"
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = arrayOf("fixture")
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = arrayOf("fixture")
        }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        return SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
            init(arrayOf(manager), arrayOf(trust), SecureRandom())
        }
    }
    private class NativePeer(password: ByteArray) : AutoCloseable {
        private val clazz = Class.forName("moe.shizuku.manager.adb.PairingContext")
        private val ptr = clazz.getDeclaredMethod("nativeConstructor", Boolean::class.javaPrimitiveType, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(null, false, password) as Long
        private val peer = clazz.getDeclaredConstructor(Long::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(ptr)
        fun message() = clazz.getMethod("getMsg").apply { isAccessible = true }.invoke(peer) as ByteArray
        fun init(message: ByteArray) = clazz.getMethod("initCipher", ByteArray::class.java).apply { isAccessible = true }.invoke(peer, message) as Boolean
        fun encrypt(message: ByteArray) = clazz.getMethod("encrypt", ByteArray::class.java).apply { isAccessible = true }.invoke(peer, message) as ByteArray?
        fun decrypt(message: ByteArray) = clazz.getMethod("decrypt", ByteArray::class.java).apply { isAccessible = true }.invoke(peer, message) as ByteArray?
        override fun close() { clazz.getMethod("destroy").apply { isAccessible = true }.invoke(peer) }
    }
    private fun readPacket(input: DataInputStream, type: Int): ByteArray {
        assertEquals(1, input.readUnsignedByte()); assertEquals(type, input.readUnsignedByte())
        val size = input.readInt(); require(size in 1..16384)
        return ByteArray(size).also { input.readFully(it) }
    }
    private fun writePacket(output: DataOutputStream, type: Int, bytes: ByteArray) {
        output.writeByte(1); output.writeByte(type); output.writeInt(bytes.size); output.write(bytes); output.flush()
    }

    private fun pairingFixture(clientCode: String): Boolean {
        val key = identity()
        val context = serverContext(key)
        val pool = Executors.newSingleThreadExecutor()
        val server = context.serverSocketFactory.createServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server.soTimeout = 10000
        try {
            // Loads the exact packaged native library before the fixture constructs its server peer.
            val client = AdbPairingClient("127.0.0.1", server.localPort, clientCode, key)
            val fixture = pool.submit<Boolean> {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10000; socket.startHandshake()
                    val material = Conscrypt.exportKeyingMaterial(socket, "adb-label\u0000", null, 64)
                    NativePeer("001234".toByteArray() + material).use { peer ->
                        val input = DataInputStream(socket.inputStream); val output = DataOutputStream(socket.outputStream)
                        val theirs = readPacket(input, 0)
                        writePacket(output, 0, peer.message()); assertTrue(peer.init(theirs))
                        val clear = peer.decrypt(readPacket(input, 1)) ?: return@submit false
                        assertEquals(8192, clear.size); assertEquals(0, clear[0].toInt())
                        assertArrayEquals(key.adbPublicKey, clear.copyOfRange(1, key.adbPublicKey.size + 1))
                        val response = ByteArray(8192).apply { this[0] = 1 }
                        writePacket(output, 1, peer.encrypt(response)!!)
                    }
                }
                true
            }
            val success = client.use { runCatching { it.start() }.getOrDefault(false) }
            assertEquals(success, fixture.get(15, TimeUnit.SECONDS))
            return success
        } finally { server.close(); pool.shutdownNow() }
    }

    @Test fun bundledNativePairingCompletesTlsSpakeAndPeerExchange() { assertTrue(pairingFixture("001234")) }
    @Test fun wrongPairingCodeIsRejectedWithoutGrant() { assertFalse(pairingFixture("654321")) }
    @Test fun connectionFailureClosesSafelyAndRejectsRemoteHost() {
        val key = identity()
        AdbPairingClient("192.0.2.1", 12345, "001234", key).use { assertTrue(runCatching { it.start() }.isFailure) }
        AdbClient("192.0.2.1", 12345, key).use { assertTrue(runCatching { it.connect() }.isFailure) }
        val port = ServerSocket(0).use { it.localPort }
        AdbPairingClient("127.0.0.1", port, "001234", key).use { assertTrue(runCatching { it.start() }.isFailure) }
    }

    @Test fun pairedTlsConnectionSendsOnlyTheFixedGrantCommand() {
        val key = identity(); val context = serverContext(key)
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10000
            fun read(input: DataInputStream): AdbMessage {
                val buffer = ByteBuffer.wrap(ByteArray(24).also { input.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
                val command = buffer.int; val arg0 = buffer.int; val arg1 = buffer.int; val size = buffer.int
                val checksum = buffer.int; val magic = buffer.int; require(size in 0..4096)
                val bytes = ByteArray(size).also { input.readFully(it) }
                return AdbMessage(command, arg0, arg1, size, checksum, magic, bytes).also { it.validateOrThrow() }
            }
            fun write(output: DataOutputStream, command: Int, arg0: Int, arg1: Int, data: String = "") {
                output.write(AdbMessage(command, arg0, arg1, data).toByteArray()); output.flush()
            }
            try {
                val fixture = pool.submit<String> {
                    server.accept().use { plain ->
                        plain.soTimeout = 10000
                        val input = DataInputStream(plain.inputStream); val output = DataOutputStream(plain.outputStream)
                        assertEquals(AdbProtocol.A_CNXN, read(input).command)
                        write(output, AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0)
                        assertEquals(AdbProtocol.A_STLS, read(input).command)
                        (context.socketFactory.createSocket(plain, "127.0.0.1", server.localPort, true) as SSLSocket).use { tls ->
                            tls.useClientMode = false; tls.soTimeout = 10000; tls.startHandshake()
                            val ti = DataInputStream(tls.inputStream); val to = DataOutputStream(tls.outputStream)
                            write(to, AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "device::")
                            val open = read(ti); assertEquals(AdbProtocol.A_OPEN, open.command)
                            val command = String(open.data!!).trimEnd('\u0000')
                            write(to, AdbProtocol.A_OKAY, 42, open.arg0)
                            write(to, AdbProtocol.A_WRTE, 42, open.arg0, "fixture only")
                            assertEquals(AdbProtocol.A_OKAY, read(ti).command)
                            write(to, AdbProtocol.A_CLSE, 42, open.arg0)
                            assertEquals(AdbProtocol.A_CLSE, read(ti).command)
                            command
                        }
                    }
                }
                AdbClient("127.0.0.1", server.localPort, key).use { it.connect(); it.shellCommand(MIDUO_NAVIGATION_GRANT) {} }
                assertEquals("shell:$MIDUO_NAVIGATION_GRANT", fixture.get(15, TimeUnit.SECONDS))
            } finally { pool.shutdownNow() }
        }
    }
}
