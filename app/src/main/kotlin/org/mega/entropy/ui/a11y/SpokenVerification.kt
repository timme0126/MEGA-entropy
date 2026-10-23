package org.mega.entropy.ui.a11y

/**
 * Pure, deterministic string helpers for reading an address or
 * fingerprint aloud (F3 — spoken verification).
 *
 * Security posture: the TTS layer speaks ONLY strings composed by these
 * functions — never model output, never free text, never input that has
 * not passed through the PSBT parser. That is why the functions here are
 * pure, total and locale-free (buildString/StringBuilder only, no
 * String.format, no locale casing, no randomness): the literal string is
 * the fact, so it must be reproducible and reviewable line by line.
 *
 * Deliberately NO android imports: this file is unit-testable as plain
 * JVM code (see SpokenVerificationTest).
 */

// NATO radio alphabet, official spellings (Juliett with the doubled
// t, X-ray hyphenated — what a trained reader expects).
private val NATO_ALPHA = arrayOf(
    "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel",
    "India", "Juliett", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa",
    "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey",
    "X-ray", "Yankee", "Zulu",
)

// NATO digit convention used here: 0 = Zero, 1 = One, ... 9 = Nine.
private val NATO_DIGITS = arrayOf(
    "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
)

/**
 * Maps every character of [text] to its spoken word:
 *  - A–Z / a–z → NATO code word (case-insensitive),
 *  - 0–9 → NATO digit word,
 *  - '-', '.', ' ' → the spoken separator ("dash", "dot", "space") —
 *    addresses are base58/bech32 (alphanumeric) so this is belt-and-braces
 *    totality for any other value the caller might route here,
 *  - anything else → dropped, counted, and reported as one trailing
 *    note so the output never silently loses characters.
 *
 * Total and deterministic: every input, including empty or binary-garbage
 * strings, yields a defined string with no locale dependence.
 */
fun toNatoPhonetic(text: String): String {
    val spoken = StringBuilder()
    var skipped = 0
    for (ch in text) {
        val word = when (ch) {
            in 'A'..'Z' -> NATO_ALPHA[ch - 'A']
            in 'a'..'z' -> NATO_ALPHA[ch - 'a']
            in '0'..'9' -> NATO_DIGITS[ch - '0']
            '-' -> "dash"
            '.' -> "dot"
            ' ' -> "space"
            else -> {
                skipped++
                null
            }
        }
        if (word != null) {
            if (spoken.isNotEmpty()) spoken.append(' ')
            spoken.append(word)
        }
    }
    if (skipped > 0) {
        if (spoken.isNotEmpty()) spoken.append(", ")
        spoken.append(skipped)
        spoken.append(if (skipped == 1) " character skipped" else " characters skipped")
    }
    return spoken.toString()
}

/**
 * Deterministic grouping of [value] into [groupSize]-character chunks
 * with the trailing remainder kept as its own final group (e.g. 14 chars
 * at group size 4 → 4+4+4+2). Empty input → empty list.
 */
fun chunkForSpeech(value: String, groupSize: Int = 4): List<String> {
    require(groupSize > 0) { "groupSize must be positive" }
    return value.chunked(groupSize)
}

/**
 * Builds the LITERAL string handed to TextToSpeech for a
 * receive/destination address: the 4-character chunk groups, the NATO
 * words of the first and last two characters, then the whole thing
 * AGAIN — the twice-spoken pattern from the F3 spec, so the listener can
 * write it down once and cross-check the second pass. Groups are
 * comma-separated so any TTS engine inserts a pause at each boundary.
 *
 * Pure string function: same address → byte-identical output, always.
 */
fun buildAddressAnnouncement(address: String, groupSize: Int = 4): String {
    val chunks = chunkForSpeech(address, groupSize).joinToString(", ")
    val firstPhonetic = toNatoPhonetic(address.take(2))
    val lastPhonetic = toNatoPhonetic(address.takeLast(2))
    val pass = buildString {
        append(chunks)
        append(". Starts with ")
        append(firstPhonetic)
        append(", ends with ")
        append(lastPhonetic)
        append(".")
    }
    return buildString {
        append("Address in groups of ")
        append(groupSize)
        append(". ")
        append(pass)
        append(" Once more. ")
        append(pass)
        append(" Check every group against the screen before you trust it.")
    }
}

/**
 * Builds the LITERAL string for an 8-character master-key fingerprint:
 * the standard practice of reading it as 4 two-character pairs, each
 * pair expanded to its NATO words ("Alpha Foxtrot, Zero Echo, ..."),
 * spoken twice. Any length is handled deterministically (pair-chunked),
 * though 8 hex chars is the expected input.
 */
fun buildFingerprintAnnouncement(hex: String): String {
    val pairs = chunkForSpeech(hex, 2).joinToString(", ") { pair -> toNatoPhonetic(pair) }
    return buildString {
        append("Fingerprint in pairs of characters. ")
        append(pairs)
        append(". Once more. ")
        append(pairs)
        append(". Check every pair against the screen before you trust it.")
    }
}
