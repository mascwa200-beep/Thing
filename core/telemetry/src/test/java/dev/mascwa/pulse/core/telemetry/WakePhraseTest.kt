package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {

    @Test fun theFuzzyPassActuallyRunsForAnEightLetterWord() {
        // THE regression test, and the reason this core exists at all. The matcher this replaces
        // gated its fuzzy pass on `token.length in 4..7`, sized for "jarvis". "computer" is eight
        // characters, so that window would have excluded the word itself from its own fuzzy match —
        // strict matches would keep working, the lenient ones would be quietly dead, and nothing
        // would report it.
        assertTrue(WordLengthIsOutsideTheOldWindow)
        // A single-substitution mishearing, which only the fuzzy pass can catch.
        assertTrue(WakePhrase.matches("compuher"))
        assertTrue(WakePhrase.matches("dompute r".replace(" ", "")))
    }

    private val WordLengthIsOutsideTheOldWindow = WakePhrase.WORD.length !in 4..7

    @Test fun theWindowIsDerivedFromTheWordSoItCannotGoStale() {
        assertEquals(WakePhrase.WORD.length - 2, WakePhrase.LENGTH_WINDOW.first)
        assertEquals(WakePhrase.WORD.length + 2, WakePhrase.LENGTH_WINDOW.last)
        assertTrue(WakePhrase.WORD.length in WakePhrase.LENGTH_WINDOW)
    }

    @Test fun theWordAndItsUsualPrefixesWake() {
        assertTrue(WakePhrase.matches("computer"))
        assertTrue(WakePhrase.matches("Computer"))
        assertTrue(WakePhrase.matches("hey computer"))
        assertTrue(WakePhrase.matches("ok computer, what is the weather"))
        assertTrue(WakePhrase.matches("okay Computer — status report"))
    }

    @Test fun theMishearingsASmallModelActuallyProducesWake() {
        for (h in WakePhrase.NEAR_HOMOPHONES) {
            assertTrue("listed homophone did not match: $h", WakePhrase.matches("$h please"))
        }
        // "commuter" is one substitution away and must wake rather than be missed.
        assertEquals(1, WakePhrase.levenshtein("commuter", WakePhrase.WORD))
    }

    @Test fun ordinarySpeechDoesNotWake() {
        val quiet = listOf(
            "", "   ", "what time is it", "play some music",
            "the weather tomorrow looks fine", "call mum",
            // The old wake word must no longer do anything.
            "jarvis", "hey jarvis", "jervis",
        )
        for (t in quiet) assertFalse("woke on: '$t'", WakePhrase.matches(t))
        assertFalse(WakePhrase.matches(null))
    }

    @Test fun twoEditsAwayIsDeliberatelyNotEnough() {
        // The bar is one edit. Two from an eight-letter word reaches a lot of ordinary English, and
        // a wake word that fires on these would be worse than one that needs repeating.
        assertTrue(WakePhrase.levenshtein("compilers", WakePhrase.WORD) >= 2)
        assertFalse(WakePhrase.matches("compilers"))
        assertFalse(WakePhrase.matches("competitor"))
    }

    @Test fun theGrammarKeepsItsUnknownSink() {
        // Without [unk] the recogniser has nowhere to put everything else and is forced to match one
        // of the listed phrases, so it wakes constantly. Cheap to lose in an edit, expensive on a
        // device.
        assertTrue(WakePhrase.GRAMMAR.contains("[unk]"))
        assertTrue(WakePhrase.GRAMMAR.contains(WakePhrase.WORD))
    }

    @Test fun editDistanceIsTheOrdinaryOne() {
        assertEquals(0, WakePhrase.levenshtein("computer", "computer"))
        assertEquals(1, WakePhrase.levenshtein("computer", "computers"))
        assertEquals(8, WakePhrase.levenshtein("", "computer"))
        assertEquals(3, WakePhrase.levenshtein("kitten", "sitting"))
    }
}
