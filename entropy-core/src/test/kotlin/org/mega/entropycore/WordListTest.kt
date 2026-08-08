package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.*

class WordListTest {

    @Test
    fun `official word list has exactly 2048 entries`() {
        val wordList = loadOfficialEnglishWordList()
        assertEquals(2048, wordList.size)
    }

    @Test
    fun `official word list has no duplicates`() {
        val wordList = loadOfficialEnglishWordList()
        assertEquals(2048, wordList.toSet().size)
    }

    @Test
    fun `official word list first and last entries are correct`() {
        val wordList = loadOfficialEnglishWordList()
        assertEquals("abandon", wordList[0])
        assertEquals("zoo", wordList[2047])
    }

    @Test
    fun `official word list known fixed entries match BIP39 spec`() {
        val wordList = loadOfficialEnglishWordList()
        assertEquals("above", wordList[4])
        assertEquals("length", wordList[1024])
        assertEquals("wheel", wordList[2000])
    }

    @Test
    fun `deriveWords maps indices to correct words`() {
        val wordList = loadOfficialEnglishWordList()
        val words = deriveWords(listOf(0, 2047), wordList)
        assertEquals(listOf("abandon", "zoo"), words)
    }

    @Test
    fun `deriveWords throws on negative index`() {
        val wordList = loadOfficialEnglishWordList()
        assertThrows(IllegalArgumentException::class.java) {
            deriveWords(listOf(-1), wordList)
        }
    }

    @Test
    fun `deriveWords throws on index out of bounds (2048)`() {
        val wordList = loadOfficialEnglishWordList()
        assertThrows(IllegalArgumentException::class.java) {
            deriveWords(listOf(2048), wordList)
        }
    }

    @Test
    fun `deriveWords throws on word list with wrong size`() {
        val wordList = loadOfficialEnglishWordList()
        assertThrows(IllegalArgumentException::class.java) {
            deriveWords(listOf(0), wordList.dropLast(1))
        }
    }
}
