package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyNewsTest {

    @Test fun strongDisastersAreEmergencies() {
        assertTrue(EmergencyNews.isEmergency("Magnitude 7.1 earthquake strikes off the coast"))
        assertTrue(EmergencyNews.isEmergency("State of emergency declared as wildfires spread"))
        assertTrue(EmergencyNews.isEmergency("Tsunami warning issued after undersea quake"))
        assertTrue(EmergencyNews.isEmergency("Gas leak forces evacuation of downtown blocks"))
        assertTrue(EmergencyNews.isEmergency("Passenger plane crash reported near the airport"))
    }

    @Test fun violenceAndSecurityAreEmergencies() {
        assertTrue(EmergencyNews.isEmergency("Active shooter reported at a shopping mall"))
        assertTrue(EmergencyNews.isEmergency("Hostage situation unfolds at a bank downtown"))
        assertTrue(EmergencyNews.isEmergency("Terror attack leaves several injured in the capital"))
    }

    @Test fun moderateFiresWithoutEntertainmentContext() {
        assertTrue(EmergencyNews.isEmergency("Explosion rocks industrial district, several hurt"))
        assertTrue(EmergencyNews.isEmergency("Manhunt under way after armed gunman flees"))
    }

    @Test fun entertainmentAndSportFalsePositivesAreExcluded() {
        assertFalse(EmergencyNews.isEmergency("Box office explosion: blockbuster smashes records"))
        assertFalse(EmergencyNews.isEmergency("New Marvel series premieres to rave reviews"))
        assertFalse(EmergencyNews.isEmergency("Striker's blast wins the match in stoppage time"))
        assertFalse(EmergencyNews.isEmergency("Pop star's single tops the streaming chart"))
    }

    @Test fun ordinaryNewsIsNotAnEmergency() {
        assertFalse(EmergencyNews.isEmergency("Stocks rise as earnings beat expectations"))
        assertFalse(EmergencyNews.isEmergency("City council debates new parking rules"))
        assertFalse(EmergencyNews.isEmergency("Nuclear deal talks resume between the two nations"))
        assertFalse(EmergencyNews.isEmergency("Local team wins the championship on penalties"))
    }

    @Test fun severityRanksStrongAboveModerateAboveNone() {
        assertEquals(2, EmergencyNews.severity("Earthquake devastates the region"))
        assertEquals(1, EmergencyNews.severity("Explosion at a warehouse, cause unknown"))
        assertEquals(0, EmergencyNews.severity("Weather stays mild through the weekend"))
    }
}
