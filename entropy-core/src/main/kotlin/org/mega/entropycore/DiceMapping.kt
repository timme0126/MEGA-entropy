package org.mega.entropycore

/**
 * Maps a single physical die roll (1..6) to a base-6 digit (0..5).
 * Subtracts 1 because base-6 positional notation uses digits 0 through 5.
 * Validates input to ensure no invalid physical rolls enter the mathematical pipeline.
 */
fun mapRollToBase6(roll: Int): Int {
    require(roll in 1..6) { "Roll must be between 1 and 6 inclusive, got: $roll" }
    return roll - 1
}

/**
 * Maps a list of physical die rolls to base-6 digits.
 * Validates every element individually to ensure no invalid input silently proceeds.
 * Returns a new list to maintain immutability and purity.
 */
fun mapRollsToBase6(rolls: List<Int>): List<Int> {
    return rolls.map { mapRollToBase6(it) }
}
