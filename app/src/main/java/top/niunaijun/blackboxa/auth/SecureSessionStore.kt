package top.niunaijun.blackboxa.auth

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureSessionStore {
    private const val PREFS = "secure_auth"
    private const val LICENSE_KEY = "license_key"
    private const val LICENSE_EXPIRY = "license_expiry"
    private const val DEVICE_ID = "device_id"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "inverness_auth_key_v1"
    private const val GCM_TAG_LENGTH_BITS = 128

    fun licenseKey(context: Context): String? = read(context, LICENSE_KEY)

    fun saveLicenseKey(context: Context, licenseKey: String) {
        write(context, LICENSE_KEY, licenseKey)
    }

    fun saveLicenseExpiry(context: Context, expiryEpochSeconds: Long?) {
        if (expiryEpochSeconds == null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(LICENSE_EXPIRY).apply()
        } else {
            write(context, LICENSE_EXPIRY, expiryEpochSeconds.toString())
        }
    }

    fun licenseExpiryMillis(context: Context): Long? = read(context, LICENSE_EXPIRY)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?.times(1_000L)

    fun clearLicenseKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(LICENSE_KEY)
            .remove(LICENSE_EXPIRY)
            .apply()
    }

    fun deviceId(context: Context): String {
        read(context, DEVICE_ID)?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        return bytes.joinToString(separator = "") { "%02X".format(it) }.also {
            write(context, DEVICE_ID, it)
        }
    }

    private fun write(context: Context, name: String, value: String) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { "Secure login requires Android 6.0 or later" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(name, payload).apply()
    }

    private fun read(context: Context, name: String): String? {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { "Secure login requires Android 6.0 or later" }
        val payload = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
        return try {
            val parts = payload.split('.', limit = 2)
            if (parts.size != 2) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }
}
