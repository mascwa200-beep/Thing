package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.SecurityAudit.AppDataUsage
import dev.mascwa.pulse.core.telemetry.SecurityAudit.AppPermissions
import dev.mascwa.pulse.core.telemetry.SecurityAudit.Category
import dev.mascwa.pulse.core.telemetry.SecurityAudit.EncryptionStatus
import dev.mascwa.pulse.core.telemetry.SecurityAudit.Severity
import dev.mascwa.pulse.core.telemetry.SecurityAudit.TrustedCa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAuditTest {

    private fun app(pkg: String, system: Boolean, vararg perms: String) =
        AppPermissions(pkg, pkg.substringAfterLast('.'), system, perms.toSet())

    @Test
    fun flagsCameraPlusNetworkOnUserApp() {
        val f = SecurityAudit.permissionCombos(app("com.x.cam", false, SecurityAudit.CAMERA, SecurityAudit.INTERNET))
        assertTrue(f.any { it.id == "PERMISSIONS:CAM_NET:com.x.cam" && it.severity == Severity.WARNING })
    }

    @Test
    fun ignoresSystemApps() {
        // System apps legitimately hold everything — would be pure noise.
        val f = SecurityAudit.permissionCombos(app("com.android.sys", true, SecurityAudit.CAMERA, SecurityAudit.INTERNET, SecurityAudit.MIC))
        assertTrue(f.isEmpty())
    }

    @Test
    fun flagsMicPlusNetworkAndSmsPlusNetwork() {
        val mic = SecurityAudit.permissionCombos(app("com.x.rec", false, SecurityAudit.MIC, SecurityAudit.INTERNET))
        assertTrue(mic.any { it.id.contains("MIC_NET") })
        val sms = SecurityAudit.permissionCombos(app("com.x.sms", false, SecurityAudit.READ_SMS, SecurityAudit.INTERNET))
        assertTrue(sms.any { it.id.contains("SMS_NET") && it.severity == Severity.WARNING })
    }

    @Test
    fun noComboWhenNoNetwork() {
        // Camera alone (no INTERNET) shouldn't raise the camera+network combo.
        val f = SecurityAudit.permissionCombos(app("com.x.cam", false, SecurityAudit.CAMERA))
        assertFalse(f.any { it.id.contains("CAM_NET") })
    }

    @Test
    fun newlyGrantedRiskyDetectsDiffButNotFreshInstalls() {
        val previous = mapOf("com.x.app" to setOf(SecurityAudit.INTERNET))
        val current = listOf(
            app("com.x.app", false, SecurityAudit.INTERNET, SecurityAudit.CAMERA), // gained CAMERA
            app("com.x.new", false, SecurityAudit.MIC),                              // brand new app
        )
        val f = SecurityAudit.newlyGrantedRisky(previous, current)
        assertTrue(f.any { it.id == "PERMISSIONS:NEWGRANT:com.x.app:CAMERA" })
        // A fresh install (unknown in previous) is not a "newly granted" change.
        assertFalse(f.any { it.subject == "com.x.new" })
    }

    @Test
    fun newlyGrantedIgnoresNonRiskyPermissions() {
        val previous = mapOf("com.x.app" to emptySet<String>())
        val current = listOf(app("com.x.app", false, SecurityAudit.INTERNET)) // INTERNET isn't in RISKY set
        assertTrue(SecurityAudit.newlyGrantedRisky(previous, current).isEmpty())
    }

    @Test
    fun caFindingsFlagOnlyUserCas() {
        val cas = listOf(
            TrustedCa("CN=GTS Root R1", isSystem = true),
            TrustedCa("CN=MyCorp Proxy", isSystem = false),
        )
        val f = SecurityAudit.caFindings(cas)
        assertEquals(1, f.size)
        assertEquals("CN=MyCorp Proxy", f.first().subject)
        assertEquals(Severity.WARNING, f.first().severity)
    }

    @Test
    fun proxyFindingFlagsConfiguredProxy() {
        assertTrue(SecurityAudit.proxyFindings("10.0.0.1:8080", 0).isNotEmpty())
        assertTrue(SecurityAudit.proxyFindings(null, 0).isEmpty())
        assertTrue(SecurityAudit.proxyFindings("", 0).isEmpty())
    }

    @Test
    fun encryptionSeverityMapping() {
        assertEquals(Severity.INFO, SecurityAudit.encryptionFinding(EncryptionStatus.ENCRYPTED).severity)
        assertEquals(Severity.CRITICAL, SecurityAudit.encryptionFinding(EncryptionStatus.INACTIVE).severity)
        assertEquals(Severity.WARNING, SecurityAudit.encryptionFinding(EncryptionStatus.UNSUPPORTED).severity)
    }

    @Test
    fun exfilCorrelatesUploadWithRiskyApps() {
        val usage = listOf(
            AppDataUsage("com.x.cam", "cam", rxBytes = 0, txBytes = 50L * 1_048_576),  // 50MB up, risky
            AppDataUsage("com.x.safe", "safe", rxBytes = 0, txBytes = 80L * 1_048_576), // 80MB up, NOT risky
        )
        val f = SecurityAudit.dataExfilFindings(usage, riskyApps = setOf("com.x.cam"), txThresholdBytes = 10L * 1_048_576)
        assertEquals(1, f.size)
        assertEquals("com.x.cam", f.first().subject)
        assertEquals(Category.DATA, f.first().category)
    }

    @Test
    fun exfilRespectsThreshold() {
        val usage = listOf(AppDataUsage("com.x.cam", "cam", 0, 1L * 1_048_576)) // 1MB < 10MB threshold
        assertTrue(SecurityAudit.dataExfilFindings(usage, setOf("com.x.cam"), 10L * 1_048_576).isEmpty())
    }

    @Test
    fun finalizeAppliesWhitelistDedupeAndSort() {
        val findings = listOf(
            SecurityAudit.Finding("A", Category.DATA, Severity.INFO, "i", ""),
            SecurityAudit.Finding("B", Category.ENCRYPTION, Severity.CRITICAL, "c", ""),
            SecurityAudit.Finding("B", Category.ENCRYPTION, Severity.WARNING, "dup", ""), // dup id, lower sev
            SecurityAudit.Finding("C", Category.PERMISSIONS, Severity.WARNING, "w", ""),
        )
        val out = SecurityAudit.finalize(findings, whitelist = setOf("A"))
        // A whitelisted out; B deduped (keeps CRITICAL); sorted critical → warning.
        assertEquals(listOf("B", "C"), out.map { it.id })
        assertEquals(Severity.CRITICAL, out.first().severity)
    }

    @Test
    fun summarizeCounts() {
        val findings = listOf(
            SecurityAudit.Finding("A", Category.DATA, Severity.INFO, "", ""),
            SecurityAudit.Finding("B", Category.DATA, Severity.WARNING, "", ""),
            SecurityAudit.Finding("C", Category.DATA, Severity.CRITICAL, "", ""),
            SecurityAudit.Finding("D", Category.DATA, Severity.WARNING, "", ""),
        )
        val s = SecurityAudit.summarize(findings)
        assertEquals(1, s.critical)
        assertEquals(2, s.warning)
        assertEquals(1, s.info)
        assertEquals(4, s.total)
    }
}
