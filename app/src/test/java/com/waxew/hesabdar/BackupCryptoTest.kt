package com.waxew.hesabdar

import com.waxew.hesabdar.data.BackupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** تست بردار استاندارد PBKDF2-HMAC-SHA256 و تشخیص Header دیتابیس. */
class BackupCryptoTest {

    @Test
    fun pbkdf2Sha256_matchesKnownVector() {
        val actual = BackupCrypto.pbkdf2Sha256(
            password = "password".toByteArray(),
            salt = "salt".toByteArray(),
            iterations = 1,
            outputBytes = 32
        )

        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            actual.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        )
    }

    @Test
    fun sqliteHeader_isRecognized() {
        assertTrue(BackupCrypto.hasSqliteHeader("SQLite format 3\u0000payload".toByteArray(Charsets.US_ASCII)))
        assertFalse(BackupCrypto.hasSqliteHeader("not-sqlite".toByteArray()))
    }
}
