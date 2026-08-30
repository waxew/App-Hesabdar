package com.waxew.hesabdar.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup قابل‌انتقال و رمزگذاری‌شده حسابدار.
 *
 * قالب فایل HDBX شامل نسخه قالب، Salt، IV و Ciphertext است. کلید از رمز عبور کاربر با PBKDF2-HMAC-SHA256
 * مشتق می‌شود و خود رمز یا کلید هرگز داخل فایل ذخیره نمی‌شود. AES-GCM هم محرمانگی و هم صحت داده را کنترل می‌کند.
 * این طراحی عمداً به Android Keystore وابسته نیست تا Backup روی دستگاه دیگری نیز با همان رمز قابل بازیابی باشد.
 */
class PortableBackupManager(
    private val context: Context,
    private val database: AppDatabase
) {
    private val secureRandom = SecureRandom()
    private val dbName = "hesabdar.db"

    /** ساخت Backup رمزگذاری‌شده با پسوند hdbx در فضای فایل‌های برنامه. */
    fun createEncryptedBackup(password: String): File {
        validatePassword(password)

        // قبل از خواندن فایل SQLite، WAL به فایل اصلی منتقل می‌شود تا Backup یک Snapshot کامل باشد.
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        val source = context.getDatabasePath(dbName)
        require(source.exists() && source.length() > 0) { "فایل دیتابیس برای پشتیبان‌گیری پیدا نشد." }

        val salt = ByteArray(SALT_SIZE).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_SIZE).also(secureRandom::nextBytes)
        val keyBytes = BackupCrypto.pbkdf2Sha256(password.toByteArray(Charsets.UTF_8), salt, PBKDF2_ITERATIONS, KEY_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(MAGIC.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(source.readBytes())
        keyBytes.fill(0)

        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "Hesabdar_${System.currentTimeMillis()}.hdbx")
        DataOutputStream(target.outputStream().buffered()).use { output ->
            output.writeUTF(MAGIC)
            output.writeInt(PBKDF2_ITERATIONS)
            output.writeInt(salt.size)
            output.write(salt)
            output.writeInt(iv.size)
            output.write(iv)
            output.writeInt(encrypted.size)
            output.write(encrypted)
        }
        return target
    }

    /**
     * بازیابی Backup رمزگذاری‌شده.
     * قبل از جایگزینی دیتابیس، یک Backup اضطراری محلی از وضعیت فعلی ایجاد می‌شود.
     * پس از موفقیت، UI باید برنامه را Restart کند تا Singleton دیتابیس دوباره باز شود.
     */
    fun restoreEncryptedBackup(backupFile: File, password: String): File {
        validatePassword(password)
        require(backupFile.exists() && backupFile.length() > 0) { "فایل Backup معتبر نیست." }

        val decrypted = try {
            DataInputStream(backupFile.inputStream().buffered()).use { input ->
                require(input.readUTF() == MAGIC) { "قالب فایل Backup توسط این نسخه پشتیبانی نمی‌شود." }
                val iterations = input.readInt()
                require(iterations in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS) { "پارامتر امنیتی Backup نامعتبر است." }

                val saltSize = input.readInt()
                require(saltSize in 16..64) { "Salt فایل Backup نامعتبر است." }
                val salt = ByteArray(saltSize).also(input::readFully)

                val ivSize = input.readInt()
                require(ivSize in 12..32) { "IV فایل Backup نامعتبر است." }
                val iv = ByteArray(ivSize).also(input::readFully)

                val encryptedSize = input.readInt()
                require(encryptedSize > 16 && encryptedSize <= MAX_BACKUP_BYTES) { "اندازه Backup نامعتبر است." }
                val encrypted = ByteArray(encryptedSize).also(input::readFully)

                val keyBytes = BackupCrypto.pbkdf2Sha256(password.toByteArray(Charsets.UTF_8), salt, iterations, KEY_SIZE)
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                    cipher.updateAAD(MAGIC.toByteArray(Charsets.UTF_8))
                    cipher.doFinal(encrypted)
                } finally {
                    keyBytes.fill(0)
                }
            }
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("رمز عبور اشتباه است یا فایل Backup آسیب دیده است.")
        }

        // SQLite header علاوه بر GCM یک کنترل ساختاری روشن برای جلوگیری از Restore فایل اشتباه فراهم می‌کند.
        require(BackupCrypto.hasSqliteHeader(decrypted)) { "محتوای Backup یک دیتابیس معتبر حسابدار نیست." }

        val emergency = BackupManager(context, database).createBackup()
        database.close()
        val target = context.getDatabasePath(dbName)
        target.parentFile?.mkdirs()
        target.writeBytes(decrypted)
        decrypted.fill(0)
        File("${target.path}-wal").delete()
        File("${target.path}-shm").delete()
        return emergency
    }

    private fun validatePassword(password: String) {
        require(password.length >= 8) { "رمز Backup باید حداقل ۸ کاراکتر باشد." }
        require(password.length <= 128) { "رمز Backup بیش از حد طولانی است." }
    }

    companion object {
        private const val MAGIC = "HESABDAR-HDBX-1"
        private const val PBKDF2_ITERATIONS = 210_000
        private const val MIN_ACCEPTED_ITERATIONS = 100_000
        private const val MAX_ACCEPTED_ITERATIONS = 1_000_000
        private const val SALT_SIZE = 16
        private const val IV_SIZE = 12
        private const val KEY_SIZE = 32
        private const val GCM_TAG_BITS = 128
        private const val MAX_BACKUP_BYTES = 512 * 1024 * 1024
    }
}

/** توابع رمزنگاری خالص و قابل Unit Test برای فرمت Backup. */
object BackupCrypto {
    private val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * PBKDF2-HMAC-SHA256 مطابق RFC 8018.
     * پیاده‌سازی مستقیم باعث می‌شود روی minSdk=24 به Provider خاص PBKDF2WithHmacSHA256 وابسته نباشیم.
     */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, outputBytes: Int): ByteArray {
        require(password.isNotEmpty()) { "Password bytes cannot be empty." }
        require(salt.isNotEmpty()) { "Salt cannot be empty." }
        require(iterations > 0) { "Iterations must be positive." }
        require(outputBytes > 0) { "Output size must be positive." }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (outputBytes + hLen - 1) / hLen
        val result = ByteArray(blocks * hLen)
        var resultOffset = 0

        for (blockIndex in 1..blocks) {
            val blockSalt = ByteArray(salt.size + 4)
            salt.copyInto(blockSalt)
            blockSalt[blockSalt.lastIndex - 3] = (blockIndex ushr 24).toByte()
            blockSalt[blockSalt.lastIndex - 2] = (blockIndex ushr 16).toByte()
            blockSalt[blockSalt.lastIndex - 1] = (blockIndex ushr 8).toByte()
            blockSalt[blockSalt.lastIndex] = blockIndex.toByte()

            var u = mac.doFinal(blockSalt)
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            t.copyInto(result, resultOffset)
            resultOffset += t.size
            u.fill(0)
            t.fill(0)
            blockSalt.fill(0)
        }

        return result.copyOf(outputBytes).also { result.fill(0) }
    }

    /** کنترل Header استاندارد SQLite. */
    fun hasSqliteHeader(bytes: ByteArray): Boolean {
        if (bytes.size < sqliteHeader.size) return false
        return sqliteHeader.indices.all { bytes[it] == sqliteHeader[it] }
    }
}
