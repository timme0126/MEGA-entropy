package org.mega.entropy.security.pin

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Low-level persistence layer for PIN state.
 *
 * All file operations are synchronous here; PinManager wraps calls in
 * withContext(Dispatchers.IO) to comply with coroutine I/O requirements.
 * File parsing fails closed: any malformed existing file throws
 * IllegalStateException rather than returning null or guessing, ensuring
 * we never silently accept corrupted security state.
 *
 * Takes the base directory directly (the caller passes context.filesDir)
 * rather than a Context, so this class — and PinManager above it — can be
 * unit-tested with plain JVM File I/O (a temp directory), with no
 * Android framework dependency, mock, or Robolectric needed.
 */
class PinStore(private val baseDir: File) {
    private fun securityDir(): File {
        val dir = File(baseDir, "mega_security")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun pinRecordFile(): File = File(securityDir(), "pin.record")
    private fun duressPinRecordFile(): File = File(securityDir(), "pin.duress.record")
    private fun attemptStateFile(): File = File(securityDir(), "pin.attempts")

    // Hex encoding/decoding helpers for plain-text storage format
    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun readPinRecord(): PinRecord? = readRecord(pinRecordFile(), "pin.record")

    fun readDuressPinRecord(): PinRecord? = readRecord(duressPinRecordFile(), "pin.duress.record")

    private fun readRecord(file: File, label: String): PinRecord? {
        if (!file.exists()) return null

        return try {
            val lines = file.readLines(StandardCharsets.UTF_8)
            if (lines.size != 5) throw IllegalStateException("Malformed $label: expected 5 lines, got ${lines.size}")
            if (lines[0] != "MEGA-PIN-V1") throw IllegalStateException("Malformed $label: invalid header")

            val saltHex = lines[1].removePrefix("salt:")
            val hashHex = lines[2].removePrefix("hash:")
            val iterationsStr = lines[3].removePrefix("iterations:")
            val createdAtStr = lines[4].removePrefix("createdAt:")

            PinRecord(
                salt = saltHex.hexToByteArray(),
                hash = hashHex.hexToByteArray(),
                iterations = iterationsStr.toInt(),
                createdAtEpochMillis = createdAtStr.toLong()
            )
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Malformed $label: failed to parse fields", e)
        }
    }

    fun writePinRecord(record: PinRecord) {
        writeRecord(pinRecordFile(), record)
    }

    fun writeDuressPinRecord(record: PinRecord) {
        writeRecord(duressPinRecordFile(), record)
    }

    private fun writeRecord(file: File, record: PinRecord) {
        val content = buildString {
            appendLine("MEGA-PIN-V1")
            appendLine("salt:${record.salt.toHexString()}")
            appendLine("hash:${record.hash.toHexString()}")
            appendLine("iterations:${record.iterations}")
            appendLine("createdAt:${record.createdAtEpochMillis}")
        }
        file.writeText(content, StandardCharsets.UTF_8)
    }

    fun deletePinRecord() {
        pinRecordFile().delete()
        duressPinRecordFile().delete()
    }

    fun deleteDuressPinRecord() {
        duressPinRecordFile().delete()
    }

    fun readAttemptState(): PinAttemptState {
        val file = attemptStateFile()
        if (!file.exists()) return PinAttemptState(0, 0)

        return try {
            val lines = file.readLines(StandardCharsets.UTF_8)
            if (lines.size != 3) throw IllegalStateException("Malformed pin.attempts: expected 3 lines, got ${lines.size}")
            if (lines[0] != "MEGA-PIN-ATTEMPTS-V1") throw IllegalStateException("Malformed pin.attempts: invalid header")

            val failedStr = lines[1].removePrefix("failedAttempts:")
            val lockedStr = lines[2].removePrefix("lockedUntil:")

            PinAttemptState(
                failedAttempts = failedStr.toInt(),
                lockedUntilEpochMillis = lockedStr.toLong()
            )
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Malformed pin.attempts: failed to parse fields", e)
        }
    }

    fun writeAttemptState(state: PinAttemptState) {
        val file = attemptStateFile()
        val content = buildString {
            appendLine("MEGA-PIN-ATTEMPTS-V1")
            appendLine("failedAttempts:${state.failedAttempts}")
            appendLine("lockedUntil:${state.lockedUntilEpochMillis}")
        }
        file.writeText(content, StandardCharsets.UTF_8)
    }

    fun resetAttemptState() {
        writeAttemptState(PinAttemptState(0, 0))
    }
}
