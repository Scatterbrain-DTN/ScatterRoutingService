package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.net.wifi.p2p.WifiP2pConfig
import android.os.Parcelable

interface FakeWifiP2pConfig: Parcelable {

    fun asConfig(): WifiP2pConfig

    companion object {

        fun bandToStr(band: Int): String {
            return when(band) {
                GROUP_OWNER_BAND_AUTO -> "auto"
                GROUP_OWNER_BAND_2GHZ -> "2.4ghz"
                GROUP_OWNER_BAND_5GHZ -> "5ghz"
                else -> "invalid band"
            }
        }

        /**
         * Allow the system to pick the operating frequency from all supported bands.
         */
        const val GROUP_OWNER_BAND_AUTO = 0

        /**
         * Allow the system to pick the operating frequency from the 2.4 GHz band.
         */
        const val GROUP_OWNER_BAND_2GHZ = 1

        /**
         * Allow the system to pick the operating frequency from the 5 GHz band.
         */
        const val GROUP_OWNER_BAND_5GHZ = 2

        /**
         * The least inclination to be a group owner, to be filled in the field
         * [.groupOwnerIntent].
         */
        const val GROUP_OWNER_INTENT_MIN = 0

        /**
         * The most inclination to be a group owner, to be filled in the field
         * [.groupOwnerIntent].
         */
        const val GROUP_OWNER_INTENT_MAX = 15

        /**
         * The system can choose an appropriate owner intent value, to be filled in the field
         * [.groupOwnerIntent].
         */
        const val GROUP_OWNER_INTENT_AUTO = -1

        const val NETWORK_ID_PERSISTENT = -1
    }
}