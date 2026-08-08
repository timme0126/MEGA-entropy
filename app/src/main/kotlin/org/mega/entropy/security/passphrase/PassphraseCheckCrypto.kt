package org.mega.entropy.security.passphrase

import java.security.MessageDigest
import org.mega.entropy.security.pin.constantTimeEquals
import org.mega.entropy.security.pin.generateSalt
import org.mega.entropycore.Bip39Seed
import org.mega.entropycore.deriveSeed

/**
 * Hashes a derived BIP39 seed for later passphrase-match verification.
 *
 * Unlike PinCrypto's PBKDF2-based PIN hash, this is a single salted
 * SHA-256, not an iterated KDF: the input already went through BIP39's own
 * 2048-round PBKDF2-HMAC-SHA512 (deriveSeed), so this hash's only job is to
 * produce a fixed-size verifier for comparison — adding more iterations
 * here would just double the cost of every guess without adding entropy,
 * since a candidate guess already has to pay the 2048-round cost to even
 * produce a seed to compare.
 */
fun hashSeedForCheck(seed: Bip39Seed, salt: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(salt)
    digest.update(seed.bytes)
    return digest.digest()
}

/**
 * Builds a new PassphraseCheck for a mnemonic + passphrase pair. The
 * passphrase itself is never retained by the returned value — only a
 * random salt and the resulting hash.
 */
fun buildPassphraseCheck(words: List<String>, passphrase: String): PassphraseCheck {
    val seed = deriveSeed(words, passphrase)
    val salt = generateSalt()
    val hash = hashSeedForCheck(seed, salt)
    return PassphraseCheck(salt = salt, hash = hash)
}

/**
 * The outcome of checking a candidate passphrase against a stored
 * PassphraseCheck. `seed` is always the seed derived from the candidate
 * (needed to compute `matches` in the first place) — callers should only
 * ever surface it to the UI when `matches` is true, mirroring how
 * PassphraseScreen only reveals a seed once one has actually been
 * calculated, never for an unconfirmed guess.
 */
data class PassphraseVerification(val matches: Boolean, val seed: Bip39Seed)

/**
 * Checks whether a candidate passphrase matches a previously built
 * PassphraseCheck, without ever exposing the original (stored) passphrase.
 * Also returns the seed the candidate produced, so a caller that confirms
 * a match can show the same "reveal the resulting seed" affordance
 * PassphraseScreen already offers right after deriving one.
 */
fun checkPassphrase(words: List<String>, candidate: String, check: PassphraseCheck): PassphraseVerification {
    val candidateSeed = deriveSeed(words, candidate)
    val candidateHash = hashSeedForCheck(candidateSeed, check.salt)
    val matches = constantTimeEquals(candidateHash, check.hash)
    return PassphraseVerification(matches = matches, seed = candidateSeed)
}
