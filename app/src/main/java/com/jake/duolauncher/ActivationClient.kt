package com.jake.duolauncher

import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ActivationFailure { INVALID, BOUND, NETWORK, SERVER, RATE_LIMIT, SAVE, DEVICE }
internal class ActivationException(val reason: ActivationFailure) : Exception()

internal object ActivationClient {
    const val ENDPOINT = "https://mymiduo.xyz/api/v1/activate"
    suspend fun redeem(code: String, device: String): String = withContext(Dispatchers.IO) {
        val normalized = code.filterNot { it.isWhitespace() || it == '-' }.uppercase(java.util.Locale.ROOT)
        if (!normalized.matches(Regex("MD[0-9A-F]{40}"))) throw ActivationException(ActivationFailure.INVALID)
        val connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.instanceFollowRedirects = false // Never forward the redeem code to a redirected origin.
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            val body = JSONObject().put("code", normalized).put("device", device).toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            when (connection.responseCode) {
                200 -> {
                    val bytes = connection.inputStream.use { input ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(512)
                        while (buffer.size() <= 2048) {
                            val count = input.read(chunk, 0, minOf(chunk.size, 2049 - buffer.size()))
                            if (count < 0) break
                            buffer.write(chunk, 0, count)
                        }
                        buffer.toByteArray()
                    }
                    if (bytes.size > 2048) throw ActivationException(ActivationFailure.SERVER)
                    val receipt = JSONObject(String(bytes, Charsets.UTF_8)).optString("license")
                    if (!OfflineLicense.verify(receipt, device)) throw ActivationException(ActivationFailure.SERVER)
                    receipt
                }
                400 -> throw ActivationException(ActivationFailure.INVALID)
                409 -> throw ActivationException(ActivationFailure.BOUND)
                429 -> throw ActivationException(ActivationFailure.RATE_LIMIT)
                else -> throw ActivationException(ActivationFailure.SERVER)
            }
        } catch (e: ActivationException) { throw e }
        catch (e: java.io.IOException) { throw ActivationException(ActivationFailure.NETWORK) }
        catch (e: org.json.JSONException) { throw ActivationException(ActivationFailure.SERVER) }
        finally { connection.disconnect() }
    }
}
