package org.mega.entropy.ui.passphrase

import kotlin.math.ln

/**
 * A deliberately simple, conservative *upper bound* on passphrase
 * strength: assumes every character was chosen uniformly at random from
 * whichever character classes appear in the passphrase. This overstates
 * the real strength of anything guessable (dictionary words, names,
 * patterns) — it is not a real strength meter, just a rough "how big is
 * the space of same-shaped strings" figure, shown so a very short or
 * single-character-class passphrase visibly looks weak rather than
 * implying false confidence in something an attacker could just guess.
 */
fun estimatePassphraseStrengthBits(passphrase: String): Int {
    if (passphrase.isEmpty()) return 0
    var charsetSize = 0
    if (passphrase.any { it in 'a'..'z' }) charsetSize += 26
    if (passphrase.any { it in 'A'..'Z' }) charsetSize += 26
    if (passphrase.any { it in '0'..'9' }) charsetSize += 10
    if (passphrase.any { it == ' ' }) charsetSize += 1
    if (passphrase.any { !it.isLetterOrDigit() && it != ' ' }) charsetSize += 32
    val bitsPerChar = ln(charsetSize.coerceAtLeast(1).toDouble()) / ln(2.0)
    return (passphrase.length * bitsPerChar).toInt()
}
