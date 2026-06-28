package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorDossierTest {

    @Test
    fun codenameIsDeterministicTwoWordsUppercase() {
        val a = OperatorDossier.codename("Pixel 10 Pro XL")
        val b = OperatorDossier.codename("Pixel 10 Pro XL")
        assertEquals(a, b)
        assertEquals(2, a.split(" ").size)
        assertEquals(a, a.uppercase())
    }

    @Test
    fun codenameFallsBackOnBlankSeed() {
        assertTrue(OperatorDossier.codename("   ").isNotBlank())
    }

    @Test
    fun intelLevelWeightsProfileMostAndSaturates() {
        assertEquals(0, OperatorDossier.intelLevel(0, 0, 0))
        assertEquals(14, OperatorDossier.intelLevel(2, 0, 0))   // profile = 7 each
        assertEquals(8, OperatorDossier.intelLevel(0, 2, 0))    // objective = 4 each
        assertEquals(100, OperatorDossier.intelLevel(100, 100, 100)) // caps at 100
        // The activity term saturates so a huge log can't dominate the score.
        assertEquals(OperatorDossier.intelLevel(0, 0, 20), OperatorDossier.intelLevel(0, 0, 9999))
    }

    @Test
    fun negativeInputsClampToZero() {
        assertEquals(0, OperatorDossier.intelLevel(-5, -5, -5))
    }

    @Test
    fun classificationThresholds() {
        assertEquals("MINIMAL", OperatorDossier.classification(0))
        assertEquals("MINIMAL", OperatorDossier.classification(9))
        assertEquals("FRAGMENTARY", OperatorDossier.classification(10))
        assertEquals("PARTIAL", OperatorDossier.classification(40))
        assertEquals("COMPREHENSIVE", OperatorDossier.classification(75))
    }
}
