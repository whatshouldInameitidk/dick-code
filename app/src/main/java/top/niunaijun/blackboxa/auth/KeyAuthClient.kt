package top.niunaijun.blackboxa.auth

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.niunaijun.blackboxa.BuildConfig
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException

object KeyAuthClient {
    private const val API_URL = "https://keyauth.win/api/1.1/"
    private const val APP_NAME = "InvernessMobile"
    private const val OWNER_ID = "nQoAViOnBL"
    private const val KEYAUTH_PUBLIC_KEY_PIN = "Uph03/nw3T0tdWja1iU6bxYUBryw4jzeFdyeSuVRMEs="

    sealed class Result {
        data class Authorized(val expiryEpochSeconds: Long?) : Result()
        data class Rejected(val message: String) : Result()
    }

    suspend fun authorize(licenseKey: String, deviceId: String): Result = withContext(Dispatchers.IO) {
        try {
            val init = request(
                mapOf(
                    "type" to "init",
                    "ver" to BuildConfig.VERSION_NAME,
                    "name" to APP_NAME,
                    "ownerid" to OWNER_ID
                )
            )
            if (!init.optBoolean("success")) return@withContext Result.Rejected(init.optString("message", "KeyAuth initialization failed"))

            val sessionId = init.optString("sessionid")
            if (sessionId.isBlank()) return@withContext Result.Rejected("KeyAuth did not return a session")

            val license = request(
                mapOf(
                    "type" to "license",
                    "key" to licenseKey,
                    "hwid" to deviceId,
                    "sessionid" to sessionId,
                    "name" to APP_NAME,
                    "ownerid" to OWNER_ID
                )
            )
            if (license.optBoolean("success")) {
                val expiry = license.optJSONObject("info")
                    ?.optJSONArray("subscriptions")
                    ?.optJSONObject(0)
                    ?.optString("expiry")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                Result.Authorized(expiry)
            }
            else Result.Rejected(license.optString("message", "License was rejected"))
        } catch (error: Exception) {
            Result.Rejected(error.message ?: "Could not contact KeyAuth")
        }
    }

    private fun request(parameters: Map<String, String>): JSONObject {
        val connection = (URL(API_URL).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(parameters.entries.joinToString("&") {
                    "${URLEncoder.encode(it.key, "UTF-8") }=${URLEncoder.encode(it.value, "UTF-8") }"
                })
            }
            connection.connect()
            validatePinnedCertificate(connection)
            val response = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = response?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (body.isBlank()) throw IllegalStateException("KeyAuth returned an empty response")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun validatePinnedCertificate(connection: HttpsURLConnection) {
        val pinMatched = connection.serverCertificates
            .filterIsInstance<X509Certificate>()
            .map { certificate ->
                Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded),
                    Base64.NO_WRAP
                )
            }
            .any(KEYAUTH_PUBLIC_KEY_PIN::equals)
        if (!pinMatched) throw SSLPeerUnverifiedException("KeyAuth certificate pin mismatch")
    }
}
