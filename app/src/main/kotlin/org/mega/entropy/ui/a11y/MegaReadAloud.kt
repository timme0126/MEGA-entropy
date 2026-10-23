package org.mega.entropy.ui.a11y

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import org.mega.entropy.ui.components.rememberMegaAnnounce

/*
 * ============================================================================
 * CRITICAL INVARIANT — read this before touching anything in this file.
 *
 * TextToSpeech here speaks ONLY literal strings composed by the pure
 * functions in SpokenVerification.kt (buildAddressAnnouncement /
 * buildFingerprintAnnouncement), applied synchronously to a value the app
 * itself derived (a parsed PSBT output address, a verified fingerprint).
 *
 * It must NEVER be handed: model/LLM output, paraphrases, string
 * concatenation of unverified external text, or any raw string that has
 * not passed the app's own parse/validate path. The spoken string IS the
 * verification fact — determinism and reviewability of that string are
 * the security property. Callers pass the raw value plus a
 * `buildAnnouncement` reference to one of the pure functions; the
 * composition always happens here, in this one place, from pure code.
 * ============================================================================
 */

private const val UTTERANCE_ID = "mega-verify"

/**
 * Compact "Read aloud" action for a verified value (an address or a
 * fingerprint). Renders a plain text TextButton — the repo's idiom for
 * low-emphasis inline actions (see the dialog buttons in
 * MegaComponents / SavedSessionDetailScreen) — so it stays visually
 * minimal inside the PSBT output rows.
 *
 * [value] is the raw app-derived string (e.g. a PSBT output address);
 * [buildAnnouncement] MUST be one of the pure functions from
 * SpokenVerification.kt — see the invariant above. [label] doubles as
 * the button's accessible name (a plain focused TextButton is announced
 * from its own text), so give it row context, e.g.
 * "Read output 2 aloud".
 *
 * TTS is created lazily on the FIRST press (engines cost memory; the
 * review screen is a hot path) and shut down on dispose. If the engine
 * is missing or fails to initialize, the literal string is copied to the
 * clipboard instead and the fallback is announced via the F1
 * announcement helper — the user still gets the exact same string, just
 * by paste instead of by ear.
 */
@Composable
fun MegaReadAloudButton(
    value: String,
    buildAnnouncement: (String) -> String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val clipboard = LocalClipboardManager.current
    val announce = rememberMegaAnnounce()
    val speaker = remember(context) { MegaTtsSpeaker(context) }
    DisposableEffect(speaker) {
        onDispose { speaker.shutdown() }
    }
    TextButton(
        onClick = {
            // The literal is composed here, by the pure function, from the
            // app-derived value — never from anything else.
            val literal = buildAnnouncement(value)
            speaker.speak(literal) {
                clipboard.setText(AnnotatedString(literal))
                announce("Speech unavailable; verification text copied")
            }
        },
        modifier = modifier,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Lazy TextToSpeech lifecycle for [MegaReadAloudButton]. Plain class, no
 * Compose inside: the state machine is small enough to audit by eye.
 *
 * Idle → (first press: construct engine) → Initializing → Ready |
 * Unavailable. A press while Initializing replaces the pending literal
 * (QUEUE_FLUSH semantics: latest press wins); a press once Unavailable —
 * or if the constructor itself throws — runs the caller's
 * [onUnavailable] fallback immediately.
 */
internal class MegaTtsSpeaker(private val context: Context) {

    private enum class State { Idle, Initializing, Ready, Unavailable }

    private var state = State.Idle
    private var tts: TextToSpeech? = null
    private var pending: String? = null
    private var pendingFallback: (() -> Unit)? = null

    fun speak(literal: String, onUnavailable: () -> Unit) {
        when (state) {
            State.Ready -> speakNow(literal, onUnavailable)
            State.Unavailable -> onUnavailable()
            State.Initializing -> {
                // Engine still booting; remember the latest request.
                pending = literal
                pendingFallback = onUnavailable
            }
            State.Idle -> {
                pending = literal
                pendingFallback = onUnavailable
                state = State.Initializing
                try {
                    tts = TextToSpeech(context) { status ->
                        state = if (status == TextToSpeech.SUCCESS) State.Ready else State.Unavailable
                        drainPending()
                    }
                } catch (_: Throwable) {
                    // No engine package resolvable at all — permanent fallback.
                    state = State.Unavailable
                    drainPending()
                }
            }
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        state = State.Idle
        pending = null
        pendingFallback = null
    }

    private fun drainPending() {
        val literal = pending
        val fallback = pendingFallback
        pending = null
        pendingFallback = null
        if (literal == null) return
        if (state == State.Ready) speakNow(literal, fallback ?: {})
        else fallback?.invoke()
    }

    private fun speakNow(literal: String, onUnavailable: () -> Unit) {
        val engine = tts
        if (engine == null) {
            state = State.Unavailable
            onUnavailable()
            return
        }
        // QUEUE_FLUSH: a new press always replaces the previous reading.
        val started = engine.speak(literal, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (started != TextToSpeech.SUCCESS) {
            state = State.Unavailable
            onUnavailable()
        }
    }
}
