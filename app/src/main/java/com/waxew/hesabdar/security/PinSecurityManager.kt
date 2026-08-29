package com.waxew.hesabdar.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * نگهداری PIN به‌صورت مشتق رمزنگاری‌شده؛ خود PIN هیچ‌وقت در فایل تنظیمات ذخیره نمی‌شود.
 */
class PinSecurityManager(context: Context) {
    private val prefs = context.getSharedPreferences("hesabdar_security", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun hasPin(): Boolean = prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT)

    fun setPin(pin: String) {
        require(pin.length in 4..12 && pin.all(Char::isDigit)) { "PIN باید بین 4 تا 12 رقم باشد." }
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltText = prefs.getString(KEY_SALT, null) ?: return false
        val hashText = prefs.getString(KEY_HASH, null) ?: return false
        val salt = Base64.decode(saltText, Base64.NO_WRAP)
        val expected = Base64.decode(hashText, Base64.NO_WRAP)
        val actual = derive(pin, salt)
        if (expected.size != actual.size) return false
        var result = 0
        expected.indices.forEach { result = result or (expected[it].toInt() xor actual[it].toInt()) }
        return result == 0
    }

    fun clearPin() {
        prefs.edit().remove(KEY_SALT).remove(KEY_HASH).apply()
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
    }
}
