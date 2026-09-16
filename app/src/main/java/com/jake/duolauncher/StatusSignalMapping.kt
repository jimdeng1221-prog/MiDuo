package com.jake.duolauncher

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

internal enum class SignalElementEmphasis {
    DIM,
    NEUTRAL,
    LIT,
}

internal sealed interface WifiSignalVisual {
    data object Disconnected : WifiSignalVisual
    data class Connected(val elements: List<SignalElementEmphasis>) : WifiSignalVisual
}

internal fun wifiSignalVisual(connected: Boolean, level: Int?): WifiSignalVisual {
    if (!connected) return WifiSignalVisual.Disconnected
    if (level == null) return WifiSignalVisual.Connected(List(4) { SignalElementEmphasis.NEUTRAL })

    val activeElements = level.coerceIn(0, 4)
    return WifiSignalVisual.Connected(List(4) { index ->
        if (index < activeElements) SignalElementEmphasis.LIT else SignalElementEmphasis.DIM
    })
}

internal sealed interface CellularSignalVisual {
    data object Airplane : CellularSignalVisual
    data object Unavailable : CellularSignalVisual
    data class Available(val activeDots: Int) : CellularSignalVisual
}

internal fun cellularSignalVisual(level: Int?, airplane: Boolean): CellularSignalVisual = when {
    airplane -> CellularSignalVisual.Airplane
    level == null -> CellularSignalVisual.Unavailable
    else -> CellularSignalVisual.Available((level.coerceIn(0, 4) * 5 + 3) / 4)
}

enum class CellularTechnology(val label: String?) {
    TWO_G("2G"),
    THREE_G("3G"),
    FOUR_G("4G"),
    FIVE_G("5G"),
    UNKNOWN(null),
}

internal fun cellularTechnology(networkType: Int, overrideNetworkType: Int): CellularTechnology {
    if (overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
        overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED ||
        overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
    ) return CellularTechnology.FIVE_G
    if (overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA ||
        overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO
    ) return CellularTechnology.FOUR_G
    return when (networkType) {
        TelephonyManager.NETWORK_TYPE_NR -> CellularTechnology.FIVE_G
        TelephonyManager.NETWORK_TYPE_LTE -> CellularTechnology.FOUR_G
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> CellularTechnology.THREE_G
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_GSM -> CellularTechnology.TWO_G
        else -> CellularTechnology.UNKNOWN
    }
}
