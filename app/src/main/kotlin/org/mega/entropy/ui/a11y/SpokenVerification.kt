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
    // The REPEAT pass is spelled fully in NATO words, not raw groups:
    // a screen-reader user has no screen to cross-check against, and
    // raw 4-char groups carry 0/O and 1/l/I homophones. The NATO pass
    // is the unambiguous second read for exactly the user this
    // feature exists to protect.
    val natoPass = chunkForSpeech(address, groupSize).joinToString(", ") { chunk ->
        toNatoPhonetic(chunk)
    }
    return buildString {
        append("Address in groups of ")
        append(groupSize)
        append(". ")
        append(pass)
        append(" Once more, phonetically. ")
        append(natoPass)
        append(". Check every group against your own records before you trust it.")
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

// English number words, spelled out, for deterministic ordinal labels
// ("Word twenty four:", "Roll one hundred:"). ONES covers 1–19 (the
// teens are irregular), TENS the regular decades; "hundred" composes.
// 1..999 is total by construction and comfortably covers every count
// this app can produce (50 or 100 rolls, 12 or 24 words).
private val ONES = arrayOf(
    "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
    "seventeen", "eighteen", "nineteen",
)
private val TENS = arrayOf(
    "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
)

/**
 * Deterministic, locale-free English number word for [n] (1–999);
 * out-of-range input falls back to its raw digits, so the function is
 * total and never throws. Hyphens follow ordinary English convention
 * ("twenty-one") — no String.format, no locale casing anywhere.
 */
private fun englishNumber(n: Int): String {
    if (n !in 1..999) return n.toString()
    val hundreds = n / 100
    val rest = n % 100
    return buildString {
        if (hundreds > 0) {
            append(ONES[hundreds - 1])
            append(" hundred")
            if (rest > 0) append(' ')
        }
        when {
            rest in 1..19 -> append(ONES[rest - 1])
            rest >= 20 -> {
                append(TENS[rest / 10])
                if (rest % 10 > 0) {
                    append('-')
                    append(ONES[rest % 10 - 1])
                }
            }
        }
    }
}

/**
 * Builds the LITERAL string handed to TextToSpeech for a revealed
 * BIP39 mnemonic: each position announced as "Word one: <word>." with
 * the index spelled out in English (a screen-reader user scrolling a
 * 24-entry grid loses track of WHICH word they are on far more easily
 * than of the word itself, so the ordinal is part of the fact).
 * BIP39 words are already plain lowercase english from the wordlist,
 * so the word itself is passed through verbatim — the only
 * composition here is the deterministic ordinal.
 *
 * Total for any list size (the app only ever produces 12 or 24); an
 * empty list yields just the trailing verification footer. Same words
 * in the same order → byte-identical output, always.
 */
fun buildMnemonicAnnouncement(words: List<String>): String {
    return buildString {
        words.forEachIndexed { index, word ->
            if (index > 0) append(' ')
            append("Word ")
            append(englishNumber(index + 1))
            append(": ")
            append(word)
            append('.')
        }
        append(" Check every word against your written backup before you trust it.")
    }
}

/**
 * Builds the LITERAL string for reviewing entered dice rolls against the
 * physical dice: each position announced as "Roll one: Four." — the
 * ordinal spelled out (see [buildMnemonicAnnouncement] for why the
 * ordinal is part of the fact), the value as its NATO digit word (same
 * mapping as the address/fingerprint passes: a spoken "four" can never
 * be misheard as the glyph '4' losing its way onto the wrong slot).
 * The closing count sentence is derived from `rolls.size` itself, so a
 * mismatch between the rolls spoken and the number claimed is
 * impossible by construction — no caller-supplied count exists anywhere
 * in this pipeline.
 *
 * Total for any list (the app produces exactly 50 or 100); an empty
 * list yields just the count + footer. Deterministic: same sequence →
 * byte-identical output, always.
 */
fun buildRollsAnnouncement(rolls: List<Int>): String {
    return buildString {
        rolls.forEachIndexed { index, roll ->
            if (index > 0) append(' ')
            append("Roll ")
            append(englishNumber(index + 1))
            append(": ")
            append(toNatoPhonetic(roll.toString()))
            append('.')
        }
        append(" That is ")
        append(rolls.size)
        append(if (rolls.size == 1) " roll." else " rolls.")
        append(" Check every roll against your dice before you trust it.")
    }
}

/**
 * Builds the LITERAL string for a GENERATED passphrase shown once on
 * screen (never a user-typed one — this builder is only ever called
 * with the app's own generated value): raw 4-character groups first,
 * then the same groups AGAIN spelled in NATO words, exactly the
 * two-pass shape of [buildAddressAnnouncement]. The raw pass lets a
 * sighted helper read it back to the owner; the NATO pass is the
 * unambiguous spelling a screen-reader user (or anyone near-homophone
 * prone: 0/O, 1/l/I) actually writes down from.
 *
 * Security posture of the CALLER (CustodyBackupScreen): TextToSpeech is
 * offline/local on stock Android and the output is audio in the user's
 * own room — this is the user's own generated secret, spoken solely so
 * they can verify what they write down or paste; it is never logged,
 * never leaves the device, and is composed only by this pure function.
 *
 * Pure and deterministic: same passphrase → byte-identical output.
 */
fun buildPassphraseAnnouncement(passphrase: String): String {
    val chunks = chunkForSpeech(passphrase, 4).joinToString(", ")
    val natoPass = chunkForSpeech(passphrase, 4).joinToString(", ") { chunk ->
        toNatoPhonetic(chunk)
    }
    return buildString {
        append("Passphrase in groups of ")
        append(4)
        append(". ")
        append(chunks)
        append(". Once more, phonetically. ")
        append(natoPass)
        append(". Check every character against your written copy.")
    }
}
