package com.jake.duolauncher

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusSignalMappingTest {
    @Test fun wifiLevelsLightDotThenThreeArcs() {
        val visuals = (0..4).map { level ->
            wifiSignalVisual(connected = true, level = level) as WifiSignalVisual.Connected
        }

        visuals.forEachIndexed { level, visual ->
            assertEquals(level, visual.elements.count { it == SignalElementEmphasis.LIT })
            assertEquals(4 - level, visual.elements.count { it == SignalElementEmphasis.DIM })
        }
        assertEquals(5, visuals.map { it.elements }.distinct().size)
    }

    @Test fun connectedUnknownWifiUsesNeutralElements() {
        val visual = wifiSignalVisual(connected = true, level = null) as WifiSignalVisual.Connected

        assertEquals(List(4) { SignalElementEmphasis.NEUTRAL }, visual.elements)
    }

    @Test fun disconnectedWifiHasNoSignalLevel() {
        assertEquals(WifiSignalVisual.Disconnected, wifiSignalVisual(connected = false, level = 4))
    }

    @Test fun cellularKeepsFiveDotConversionForLevelsZeroThroughFour() {
        assertEquals(listOf(0, 2, 3, 4, 5), (0..4).map { level ->
            (cellularSignalVisual(level, airplane = false) as CellularSignalVisual.Available).activeDots
        })
    }

    @Test fun cellularUnavailableAndAirplaneRemainDistinctWithoutLitDots() {
        assertEquals(CellularSignalVisual.Unavailable, cellularSignalVisual(level = null, airplane = false))
        assertEquals(CellularSignalVisual.Airplane, cellularSignalVisual(level = 4, airplane = true))
        assertTrue(cellularSignalVisual(level = null, airplane = true) is CellularSignalVisual.Airplane)
    }

    @Test fun cellularTechnologyUsesCarrierDisplayOverrideBeforeCampedNetwork() {
        assertEquals(CellularTechnology.FIVE_G, cellularTechnology(TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA))
        assertEquals(CellularTechnology.FIVE_G, cellularTechnology(TelephonyManager.NETWORK_TYPE_NR,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
        assertEquals(CellularTechnology.FOUR_G, cellularTechnology(TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
        assertEquals(CellularTechnology.THREE_G, cellularTechnology(TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
        assertEquals(CellularTechnology.TWO_G, cellularTechnology(TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
        assertEquals(CellularTechnology.UNKNOWN, cellularTechnology(TelephonyManager.NETWORK_TYPE_UNKNOWN,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
    }
}
