package org.mega.entropy.storage

data class SavedSessionMetadata(
    val id: String,
    val createdAtEpochMillis: Long,
    val rollsCount: Int,
    val hasMnemonic: Boolean,
    val keystoreAlias: String,
    // User-editable label, empty string if never set. Not sensitive (like
    // the rest of this metadata), so no validation on its contents beyond
    // not containing the line-based format's own delimiter.
    val label: String = "",
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(rollsCount in 0..100) { "rollsCount must be between 0 and 100, got $rollsCount" }
        require(keystoreAlias.isNotBlank()) { "keystoreAlias must not be blank" }
        require(!label.contains('\n')) { "label must not contain a newline" }
    }
}

data class SavedSessionRecord(
    val metadata: SavedSessionMetadata,
    val diceRolls: List<Int>,
    val mnemonicWords: List<String>?,
) {
    init {
        // rollsCount must be within the allowed range for a session
        require(metadata.rollsCount in 0..100) {
            "rollsCount must be between 0 and 100, got ${metadata.rollsCount}"
        }
        // diceRolls list size must exactly match the recorded count
        require(diceRolls.size == metadata.rollsCount) {
            "diceRolls size (${diceRolls.size}) must match rollsCount (${metadata.rollsCount})"
        }
        // mnemonicWords nullability must strictly match the hasMnemonic flag
        val mnemonicMatchesFlag = (mnemonicWords != null) == metadata.hasMnemonic
        require(mnemonicMatchesFlag) {
            "mnemonicWords nullability must match hasMnemonic flag"
        }
        // If mnemonic is present, it must be a supported MEGA length: 12 or 24 words
        if (mnemonicWords != null) {
            require(mnemonicWords.size == 12 || mnemonicWords.size == 24) {
                "mnemonicWords must contain 12 or 24 entries when present, got ${mnemonicWords.size}"
            }
        }
    }
}
