package org.mega.entropycore

/** The full BIP-380 output descriptor character set, split into 3 groups of
 * 32 so that [polyMod] can pack 3 characters' group numbers into a single
 * extra symbol — see [descriptorChecksum]'s doc comment. Ported character-
 * for-character from Bitcoin Core's INPUT_CHARSET (src/script/descriptor.cpp)
 * so this produces byte-identical checksums to Bitcoin Core, Sparrow, and
 * every other BIP-380-compliant tool. */
private const val DESCRIPTOR_INPUT_CHARSET =
    "0123456789()[],'/*abcdefgh@:\$%{}" +
        "IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~" +
        "ijklmnopqrstuvwxyzABCDEFGH`#\"\\ "

/** The checksum's own output alphabet — identical to bech32's. */
private const val DESCRIPTOR_CHECKSUM_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

/** One step of the BCH-code polynomial used by [descriptorChecksum], ported
 * directly from Bitcoin Core's PolyMod (src/script/descriptor.cpp) — same
 * generator constants, same bit widths. Not meaningful in isolation; see
 * that function for the derivation. */
private fun polyMod(c: Long, value: Int): Long {
    val c0 = c ushr 35
    var next = ((c and 0x7ffffffffL) shl 5) xor value.toLong()
    if (c0 and 1L != 0L) next = next xor 0xf5dee51989L
    if (c0 and 2L != 0L) next = next xor 0xa9fdca3312L
    if (c0 and 4L != 0L) next = next xor 0x1bab10e32dL
    if (c0 and 8L != 0L) next = next xor 0x3706b1677aL
    if (c0 and 16L != 0L) next = next xor 0x644d626ffdL
    return next
}

/**
 * Computes the 8-character BIP-380 checksum for [descriptor] — the text
 * that would follow a `#` at the end of an output descriptor. [descriptor]
 * must NOT already include a `#CHECKSUM` suffix; pass exactly the script
 * expression being checksummed (see [verifyAndStripDescriptorChecksum] for
 * consuming one, [appendDescriptorChecksum] for producing one).
 *
 * This exists so a MEGA-built descriptor round-trips cleanly through other
 * wallets (Sparrow, Bitcoin Core, hardware wallet coordinators) that expect
 * or display a checksum, and so a checksum on an imported descriptor can be
 * verified rather than silently trusted or silently discarded — the
 * checksum's whole purpose is catching a transcription error in a
 * pasted/scanned descriptor, which only works if something actually checks
 * it.
 */
fun descriptorChecksum(descriptor: String): String {
    var c = 1L
    var cls = 0
    var clsCount = 0
    for (ch in descriptor) {
        val pos = DESCRIPTOR_INPUT_CHARSET.indexOf(ch)
        require(pos >= 0) {
            "Descriptor contains a character outside the BIP-380 checksum character set: '$ch'"
        }
        c = polyMod(c, pos and 31) // Emit a symbol for the position inside the group, for every character.
        cls = cls * 3 + (pos shr 5) // Accumulate the group numbers.
        clsCount++
        if (clsCount == 3) {
            // Emit an extra symbol representing the group numbers, for every 3 characters.
            c = polyMod(c, cls)
            cls = 0
            clsCount = 0
        }
    }
    if (clsCount > 0) c = polyMod(c, cls)
    repeat(8) { c = polyMod(c, 0) } // Shift further to determine the checksum.
    c = c xor 1L // Prevent appending zeroes from not affecting the checksum.

    return buildString {
        for (j in 0 until 8) {
            append(DESCRIPTOR_CHECKSUM_CHARSET[((c shr (5 * (7 - j))) and 31L).toInt()])
        }
    }
}

/** Appends a freshly computed BIP-380 checksum to [descriptor] as
 * `descriptor#checksum` — the standard, tool-interoperable way to present
 * an output descriptor (matches what Sparrow/Bitcoin Core show). */
fun appendDescriptorChecksum(descriptor: String): String = "$descriptor#${descriptorChecksum(descriptor)}"

/**
 * Strips and validates an optional trailing `#CHECKSUM` (BIP-380) from
 * [text]. A checksum is OPTIONAL per BIP-380 — [text] with no `#` at all is
 * returned unchanged, exactly as a hand-typed or older-tool-exported
 * descriptor with no checksum should still be accepted. When a `#` IS
 * present, the 8 characters after it must be well-formed and must match
 * [descriptorChecksum] of everything before it, or this throws —
 * silently accepting a mismatched checksum would defeat its entire
 * purpose, which is catching a transcription typo in a pasted or scanned
 * descriptor.
 */
fun verifyAndStripDescriptorChecksum(text: String): String {
    val hashIndex = text.indexOf('#')
    if (hashIndex == -1) return text
    require(text.indexOf('#', hashIndex + 1) == -1) {
        "Descriptor has more than one '#' — a valid descriptor has at most one checksum."
    }
    val body = text.substring(0, hashIndex)
    val checksum = text.substring(hashIndex + 1)
    require(checksum.length == 8) {
        "Descriptor checksum must be exactly 8 characters, got ${checksum.length}."
    }
    val expected = try {
        descriptorChecksum(body)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException(
            "Descriptor contains a character outside the checksum character set — cannot verify its checksum.",
            e,
        )
    }
    require(checksum == expected) {
        "Descriptor checksum does not match (expected $expected, got $checksum) — check for a typo in the pasted/scanned text."
    }
    return body
}
