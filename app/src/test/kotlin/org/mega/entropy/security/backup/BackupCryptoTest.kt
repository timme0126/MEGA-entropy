package org.mega.entropy.security.backup

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

private fun bytes(fill: Byte, length: Int) = ByteArray(length) { fill }

class BackupCryptoTest {
    @Test
    fun `deriveBackupKey is deterministic for the same passphrase and salt`() {
        val salt = bytes(0x01, BACKUP_SALT_LENGTH_BYTES)
        val a = deriveBackupKey("correct horse battery staple", salt, n = 1024, r = 8, p = 1)
        val b = deriveBackupKey("correct horse battery staple", salt, n = 1024, r = 8, p = 1)
        assertArrayEquals(a, b)
        assertEquals(BACKUP_KEY_LENGTH_BYTES, a.size)
    }

    @Test
    fun `deriveBackupKey differs for a different passphrase or a different salt`() {
        val salt = bytes(0x01, BACKUP_SALT_LENGTH_BYTES)
        val base = deriveBackupKey("correct horse battery staple", salt, n = 1024, r = 8, p = 1)
        val differentPassphrase = deriveBackupKey("wrong passphrase", salt, n = 1024, r = 8, p = 1)
        val differentSalt = deriveBackupKey("correct horse battery staple", bytes(0x02, BACKUP_SALT_LENGTH_BYTES), n = 1024, r = 8, p = 1)
        assertFalse(base.contentEquals(differentPassphrase))
        assertFalse(base.contentEquals(differentSalt))
    }

    @Test
    fun `deriveBackupKey rejects a salt of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) {
            deriveBackupKey("passphrase", bytes(0x00, 8), n = 1024, r = 8, p = 1)
        }
    }

    @Test
    fun `encrypt then decrypt round-trips the plaintext`() {
        val key = bytes(0x42, BACKUP_KEY_LENGTH_BYTES)
        val iv = bytes(0x24, BACKUP_GCM_IV_LENGTH_BYTES)
        val aad = "header-bytes".toByteArray()
        val plaintext = "MEGA backup payload contents".toByteArray()

        val ciphertext = encryptBackupPayload(key, iv, aad, plaintext)
        val decrypted = decryptBackupPayload(key, iv, aad, ciphertext)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `decrypt fails closed with the wrong key`() {
        val iv = bytes(0x24, BACKUP_GCM_IV_LENGTH_BYTES)
        val aad = "header-bytes".toByteArray()
        val ciphertext = encryptBackupPayload(bytes(0x01, BACKUP_KEY_LENGTH_BYTES), iv, aad, "plaintext".toByteArray())

        assertThrows(AEADBadTagException::class.java) {
            decryptBackupPayload(bytes(0x02, BACKUP_KEY_LENGTH_BYTES), iv, aad, ciphertext)
        }
    }

    @Test
    fun `decrypt fails closed when the AAD (header) has been tampered with`() {
        val key = bytes(0x42, BACKUP_KEY_LENGTH_BYTES)
        val iv = bytes(0x24, BACKUP_GCM_IV_LENGTH_BYTES)
        val ciphertext = encryptBackupPayload(key, iv, "original-header".toByteArray(), "plaintext".toByteArray())

        assertThrows(AEADBadTagException::class.java) {
            decryptBackupPayload(key, iv, "tampered-header".toByteArray(), ciphertext)
        }
    }
}
