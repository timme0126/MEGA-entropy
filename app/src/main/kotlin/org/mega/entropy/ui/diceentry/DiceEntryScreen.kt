package org.mega.entropy.ui.diceentry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen

/**
 * The core interaction of the app: 20 batches of 5 physical die-roll taps,
 * per spec sections 5–8. Every press is a deliberate tap on a labeled
 * button — there is no auto-advance on a timer, no gesture that could be
 * mistaken for a different value, and Undo/Clear are always one tap away.
 */
@Composable
fun DiceEntryScreen(
    onSessionComplete: () -> Unit,
    onBack: () -> Unit,
    viewModel: DiceSessionViewModel = viewModel(),
) {
    SecureScreen()
    val state by viewModel.uiState.collectAsState()
    val haptics = LocalHapticFeedback.current

    if (state.isSessionComplete) {
        // Navigate away once the 100th roll completes the final batch;
        // the caller (nav graph) owns what "next" means (Bias Check).
        LaunchedEffect(state.isSessionComplete) {
            onSessionComplete()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Roll ${(state.rollsEntered + 1).coerceAtMost(TOTAL_ROLLS)} of $TOTAL_ROLLS",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Batch ${state.currentBatchNumber} of $TOTAL_BATCHES",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ProgressCard(state = state)

        RollSlots(currentBatchRolls = state.currentBatchRolls)

        DieButtonGrid(
            enabled = !state.isSessionComplete,
            onDieTapped = { value ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.onRollEntered(value)
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MegaSecondaryButton(
                text = "Undo",
                modifier = Modifier.weight(1f),
                enabled = state.rollsEntered > 0,
                onClick = { viewModel.undoLastRoll() },
            )
            MegaSecondaryButton(
                text = "Clear Batch",
                modifier = Modifier.weight(1f),
                enabled = state.currentBatchRolls.isNotEmpty(),
                onClick = { viewModel.clearCurrentBatch() },
            )
        }

        state.completedBatches.lastOrNull()?.let { lastBatch ->
            BatchCalculationCard(batch = lastBatch)
        }

        if (state.completedBatches.size > 1) {
            PreviousBatchesCard(batches = state.completedBatches.dropLast(1))
        }

        MegaSecondaryButton(text = "Back", onClick = onBack)
    }
}

@Composable
private fun ProgressCard(state: DiceSessionUiState) {
    MegaCard {
        Text("${state.rollsEntered} / $TOTAL_ROLLS rolls", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { state.rollsEntered / TOTAL_ROLLS.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${state.completedBatches.size} / $TOTAL_BATCHES batches",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text(
            "Entropy source: 100% your physical dice",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Device-generated entropy used for mnemonic: NONE",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RollSlots(currentBatchRolls: List<Int>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for (i in 0 until ROLLS_PER_BATCH) {
            val value = currentBatchRolls.getOrNull(i)
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = value?.toString() ?: "_",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (value != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DieButtonGrid(enabled: Boolean, onDieTapped: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            (1..3).forEach { DieButton(value = it, enabled = enabled, onTapped = onDieTapped, modifier = Modifier.weight(1f)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            (4..6).forEach { DieButton(value = it, enabled = enabled, onTapped = onDieTapped, modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DieButton(value: Int, enabled: Boolean, onTapped: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(enabled = enabled) { onTapped(value) },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(text = value.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BatchCalculationCard(batch: CompletedBatch) {
    var showMath by remember { mutableStateOf(false) }
    var showTechnical by remember { mutableStateOf(false) }

    MegaCard(title = "Batch ${batch.batchNumber} calculation") {
        Text("Physical rolls", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MegaMonoText(batch.physicalRolls.joinToString("   "))

        Text("Converted to base 6", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MegaMonoText(batch.base6Digits.joinToString("   "))
        Text(
            "We subtract 1 from each die so the six possible outcomes become the six base-6 digits 0 through 5.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = if (showMath) "Hide the math" else "Show the math",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { showMath = !showMath },
        )
        AnimatedVisibility(visible = showMath) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val (a, b, c, d, e) = batch.base6Digits
                MegaMonoText("${a}${b}${c}${d}${e}₆")
                MegaMonoText("$a×6⁴ + $b×6³ + $c×6² + $d×6¹ + $e×6⁰")
                MegaMonoText("${a * 1296} + ${b * 216} + ${c * 36} + ${d * 6} + $e")
                MegaMonoText("= ${batch.chunk}")
                Spacer(modifier = Modifier.height(4.dp))
                MegaMonoText("Previous X × 7776 + ${batch.chunk} = New X")
                MegaMonoText("${batch.previousX} × 7776 + ${batch.chunk} = ${batch.newX}")
            }
        }

        Text(
            text = if (showTechnical) "Hide technical details" else "Show technical details",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { showTechnical = !showTechnical },
        )
        AnimatedVisibility(visible = showTechnical) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MegaMonoText("Decimal chunk: ${batch.chunk}")
                MegaMonoText("Hex chunk: 0x${batch.chunk.toString(16).uppercase()}")
                MegaMonoText("Base-6 chunk: ${batch.base6Digits.joinToString("")}")
                MegaMonoText("Running X bit length: ${batch.newX.bitLength()} bits")
                MegaMonoText("Running X: ${batch.newX}")
            }
        }
    }
}

@Composable
private fun PreviousBatchesCard(batches: List<CompletedBatch>) {
    var expanded by remember { mutableStateOf(false) }
    MegaCard {
        Text(
            text = if (expanded) "Hide previous batches" else "Review previous batches (${batches.size})",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = !expanded },
        )
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                batches.forEach { batch ->
                    HorizontalDivider()
                    Text("Batch ${batch.batchNumber}: ${batch.physicalRolls.joinToString(" ")}", style = MaterialTheme.typography.bodyMedium)
                    MegaMonoText("chunk=${batch.chunk}  X=${batch.newX}")
                }
            }
        }
    }
}
