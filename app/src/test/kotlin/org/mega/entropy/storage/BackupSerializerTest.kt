package org.mega.entropy.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mega.entropy.security.backup.BACKUP_SCRYPT_MAX_N
import org.mega.entropy.security.passphrase.PassphraseCheck
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork

private val TWELVE_WORDS = List(12) { "abandon" }
private val TWENTY_FOUR_WORDS = List(24) { "abandon" }

class BackupSerializerTest {
    // --- outer file (header + ciphertext) ---

    @Test
    fun `encodeBackupFile then decodeBackupFile round-trips the header and ciphertext`() {
        val header = BackupHeader(n = 131072, r = 8, p = 1, salt = ByteArray(16) { 0x01 }, iv = ByteArray(12) { 0x02 })
        val ciphertext = byteArrayOf(1, 2, 3, 4, 5, -1, 0, 127)

        val file = decodeBackupFile(encodeBackupFile(header, ciphertext))

        assertEquals(header.n, file.header.n)
        assertEquals(header.r, file.header.r)
        assertEquals(header.p, file.header.p)
        assertEquals(header.salt.toList(), file.header.salt.toList())
        assertEquals(header.iv.toList(), file.header.iv.toList())
        assertEquals(ciphertext.toList(), file.ciphertext.toList())
    }

    @Test
    fun `decodeBackupFile's aad is exactly the header bytes actually written`() {
        val header = BackupHeader(n = 131072, r = 8, p = 1, salt = ByteArray(16) { 0x01 }, iv = ByteArray(12) { 0x02 })
        val fileBytes = encodeBackupFile(header, ciphertext = byteArrayOf(9, 9, 9))

        val file = decodeBackupFile(fileBytes)

        assertEquals(fileBytes.size - 3, file.aad.size)
        assertEquals(fileBytes.take(file.aad.size), file.aad.toList())
    }

    @Test
    fun `decodeBackupFile rejects a file with the wrong magic header`() {
        val bytes = "NOT-A-MEGA-BACKUP\nN:1\nR:1\nP:1\nSALT:00\nIV:00\nEND-HEADER\n".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { decodeBackupFile(bytes) }
    }

    @Test
    fun `decodeBackupFile rejects scrypt cost parameters above the accepted range`() {
        val header = BackupHeader(n = BACKUP_SCRYPT_MAX_N + 1, r = 8, p = 1, salt = ByteArray(16), iv = ByteArray(12))
        val bytes = encodeBackupFile(header, ciphertext = byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { decodeBackupFile(bytes) }
    }

    @Test
    fun `decodeBackupFile rejects a truncated header`() {
        assertThrows(IllegalArgumentException::class.java) { decodeBackupFile("MEGA-BACKUP-V1\nN:1\n".toByteArray()) }
    }

    // --- inner payload (sessions + vaults) ---

    @Test
    fun `round-trips an empty payload`() {
        val payload = BackupPayload(sessions = emptyList(), vaults = emptyList())
        assertEquals(payload, decodeBackupPayload(encodeBackupPayload(payload)))
    }

    @Test
    fun `round-trips sessions with and without a passphrase check, and 12- or 24-word mnemonics`() {
        val checkedSession = BackupSessionEntry(
            createdAtEpochMillis = 1_700_000_000_000L,
            label = "Cold storage",
            tags = listOf("cold", "spending"),
            childSeedInfo = "",
            mnemonicWords = TWENTY_FOUR_WORDS,
            passphraseCheck = PassphraseCheck(salt = ByteArray(16) { 0x11 }, hash = ByteArray(32) { 0x22 }),
        )
        val uncheckedSession = BackupSessionEntry(
            createdAtEpochMillis = 1_700_000_001_000L,
            label = "No passphrase check",
            tags = emptyList(),
            childSeedInfo = "Child Seed of \"Cold storage\" · Native SegWit · Index 5",
            mnemonicWords = TWELVE_WORDS,
            passphraseCheck = null,
        )
        val payload = BackupPayload(sessions = listOf(checkedSession, uncheckedSession), vaults = emptyList())

        val decoded = decodeBackupPayload(encodeBackupPayload(payload))

        // Not a plain assertEquals(payload, decoded): PassphraseCheck holds
        // ByteArray fields, and Kotlin data class equals() compares those
        // by reference, not content — a structurally-identical decoded
        // salt/hash would still fail assertEquals despite round-tripping
        // correctly. Every other field is compared directly; salt/hash are
        // compared with contentEquals instead.
        assertEquals(2, decoded.sessions.size)
        val decodedChecked = decoded.sessions[0]
        val decodedUnchecked = decoded.sessions[1]

        assertEquals(checkedSession.copy(passphraseCheck = null), decodedChecked.copy(passphraseCheck = null))
        val originalCheck = checkNotNull(checkedSession.passphraseCheck)
        val decodedCheck = checkNotNull(decodedChecked.passphraseCheck)
        assertEquals(originalCheck.salt.toList(), decodedCheck.salt.toList())
        assertEquals(originalCheck.hash.toList(), decodedCheck.hash.toList())

        assertEquals(uncheckedSession, decodedUnchecked)
    }

    @Test
    fun `round-trips vaults with every cosigner passphraseUsed state`() {
        val payload = BackupPayload(
            sessions = emptyList(),
            vaults = listOf(
                BackupVaultEntry(
                    createdAtEpochMillis = 1_700_000_000_000L,
                    label = "Family multisig",
                    threshold = 2,
                    network = WalletNetwork.MAINNET,
                    scriptType = MultisigScriptType.NATIVE_SEGWIT,
                    tags = listOf("family"),
                    cosigners = listOf(
                        BackupVaultCosignerEntry("Alice", "751e76e8", "m/48'/0'/0'/2'", "xpubAAA", passphraseUsed = true),
                        BackupVaultCosignerEntry("Bob", "06afd46b", "m/48'/0'/0'/2'", "xpubBBB", passphraseUsed = false),
                        BackupVaultCosignerEntry("Carol", "7dd65592", "m/48'/0'/0'/2'", "xpubCCC", passphraseUsed = null),
                    ),
                ),
            ),
        )

        assertEquals(payload, decodeBackupPayload(encodeBackupPayload(payload)))
    }

    @Test
    fun `decodeBackupPayload rejects a mnemonic that is not 12 or 24 words`() {
        val malformed = encodeBackupPayload(
            BackupPayload(
                sessions = listOf(
                    BackupSessionEntry(0L, "x", emptyList(), "", List(15) { "abandon" }, null),
                ),
                vaults = emptyList(),
            ),
        )
        assertThrows(IllegalStateException::class.java) { decodeBackupPayload(malformed) }
    }
}
