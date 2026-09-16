// Derived from Shizuku 2650830c (Apache-2.0), Copyright RikkaApps contributors.
// MiDuo: loopback-only transport, timeouts, bundled Conscrypt, no sensitive logging.
package moe.shizuku.manager.adb

import android.os.Build
import androidx.annotation.RequiresApi
import org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbPairClient"

private const val kCurrentKeyHeaderVersion = 1.toByte()
private const val kMinSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxPeerInfoSize = 8192
private const val kMaxPayloadSize = kMaxPeerInfoSize * 2

private const val kExportedKeyLabel = "adb-label\u0000"
private const val kExportedKeySize = 64

private const val kPairingPacketHeaderSize = 6

private class PeerInfo(
        val type: Byte,
        data: ByteArray) {

    val data = ByteArray(kMaxPeerInfoSize - 1)

    init {
        data.copyInto(this.data, 0, 0, data.size.coerceAtMost(kMaxPeerInfoSize - 1))
    }

    enum class Type(val value: Byte) {
        ADB_RSA_PUB_KEY(0.toByte()),
        ADB_DEVICE_GUID(0.toByte()),
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(type)
            put(data)
        }

    }

    override fun toString(): String {
        return "PeerInfo(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "type=$type, data=${data.contentToString()}"
    }

    companion object {

        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()
            val data = ByteArray(kMaxPeerInfoSize - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

private class PairingPacketHeader(
        val version: Byte,
        val type: Byte,
        val payload: Int) {

    enum class Type(val value: Byte) {
        SPAKE2_MSG(0.toByte()),
        PEER_INFO(1.toByte())
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(version)
            put(type)
            putInt(payload)
        }

    }

    override fun toString(): String {
        return "PairingPacketHeader(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "version=${version.toInt()}, type=${type.toInt()}, payload=$payload"
    }

    companion object {

        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int

            if (version < kMinSupportedKeyHeaderVersion || version > kMaxSupportedKeyHeaderVersion) {

                return null
            }
            if (type != Type.SPAKE2_MSG.value && type != Type.PEER_INFO.value) {

                return null
            }
            if (payload <= 0 || payload > kMaxPayloadSize) {

                return null
            }

            val header = PairingPacketHeader(version, type, payload)

            return header
        }
    }
}

private class PairingContext private constructor(private val nativePtr: Long) {

    val msg: ByteArray

    init {
        msg = nativeMsg(nativePtr)
    }

    fun initCipher(theirMsg: ByteArray) = nativeInitCipher(nativePtr, theirMsg)

    fun encrypt(`in`: ByteArray) = nativeEncrypt(nativePtr, `in`)

    fun decrypt(`in`: ByteArray) = nativeDecrypt(nativePtr, `in`)

    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray

    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean

    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDestroy(nativePtr: Long)

    companion object {

        fun create(password: ByteArray): PairingContext? {
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(private val host: String, private val port: Int, private val pairCode: String, private val key: AdbKey) : Closeable {

    private enum class State {
        Ready,
        ExchangingMsgs,
        ExchangingPeerInfo,
        Stopped
    }

    @Volatile private lateinit var socket: Socket
    @Volatile private var aborted = false
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream

    private val peerInfo: PeerInfo = PeerInfo(PeerInfo.Type.ADB_RSA_PUB_KEY.value, key.adbPublicKey)
    private lateinit var pairingContext: PairingContext
    private var state: State = State.Ready
    private var destroyed = false

    fun start(): Boolean {
        setupTlsConnection()

        state = State.ExchangingMsgs

        if (!doExchangeMsgs()) {
            state = State.Stopped
            return false
        }

        state = State.ExchangingPeerInfo

        if (!doExchangePeerInfo()) {
            state = State.Stopped
            return false
        }

        state = State.Stopped
        return true
    }

    private fun setupTlsConnection() {
        require(host == "127.0.0.1") { "Only this device is supported" }
        socket = Socket()
        check(!aborted) { "Cancelled" }
        socket.soTimeout = 12000
        socket.connect(java.net.InetSocketAddress(host, port), 8000)
        socket.tcpNoDelay = true

        val sslContext = key.sslContext
        val sslSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        sslSocket.startHandshake()

        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        val pairCodeBytes = pairCode.toByteArray()
        val keyMaterial = Conscrypt.exportKeyingMaterial(sslSocket, kExportedKeyLabel, null, kExportedKeySize)
        val passwordBytes = ByteArray(pairCodeBytes.size + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

        val pairingContext = try { PairingContext.create(passwordBytes) } finally {
            passwordBytes.fill(0); pairCodeBytes.fill(0); keyMaterial.fill(0)
        }
        checkNotNull(pairingContext) { "Unable to create PairingContext." }
        this.pairingContext = pairingContext
    }

    private fun createHeader(type: PairingPacketHeader.Type, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(kCurrentKeyHeaderVersion, type.value, payloadSize)
    }

    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(kPairingPacketHeaderSize)
        inputStream.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return PairingPacketHeader.readFrom(buffer)
    }

    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)

        outputStream.write(buffer.array())
        outputStream.write(payload)

    }

    private fun doExchangeMsgs(): Boolean {
        val msg = pairingContext.msg
        val size = msg.size

        val ourHeader = createHeader(PairingPacketHeader.Type.SPAKE2_MSG, size)
        writeHeader(ourHeader, msg)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG.value) return false

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)

        if (!pairingContext.initCipher(theirMessage)) return false
        return true
    }

    private fun doExchangePeerInfo(): Boolean {
        val buf = ByteBuffer.allocate(kMaxPeerInfoSize).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buf)

        val outbuf = pairingContext.encrypt(buf.array()) ?: return false

        val ourHeader = createHeader(PairingPacketHeader.Type.PEER_INFO, outbuf.size)
        writeHeader(ourHeader, outbuf)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO.value) return false

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)

        val decrypted = pairingContext.decrypt(theirMessage) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != kMaxPeerInfoSize) {

            return false
        }
        val theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decrypted))

        return true
    }

    fun abort() { aborted = true; if (::socket.isInitialized) runCatching { socket.close() } }

    override fun close() {
        try {
            inputStream.close()
        } catch (e: Throwable) {
        }
        try {
            outputStream.close()
        } catch (e: Throwable) {
        }
        try {
            socket.close()
        } catch (e: Exception) {
        }

        if (::pairingContext.isInitialized && !destroyed) {
            destroyed = true
            pairingContext.destroy()
        }
    }

    companion object {

        init {
            System.loadLibrary("adb")
        }


    }
}
