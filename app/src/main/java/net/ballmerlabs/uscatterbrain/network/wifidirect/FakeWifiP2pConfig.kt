package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.os.Parcel
import android.os.Parcelable

class FakeWifiP2pConfig(
        val passphrase: String? = "",
        val deviceAddress: String? = "02:00:00:00:00:00",
        val networkName: String? = "",
        val netId: Int = NETWORK_ID_PERSISTENT,
        val groupOwnerBand: Int = GROUP_OWNER_BAND_AUTO,
        val groupownerIntent: Int = GROUP_OWNER_INTENT_AUTO,
        val wpsInfo: WpsInfo? = WpsInfo(),
) : Parcelable {

    constructor(parcel: Parcel) : this()

    fun asConfig(): WifiP2pConfig {
        val parcel = Parcel.obtain()
        parcel.writeString(WifiP2pConfig::class.java.name)
        this.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        return parcel.readParcelable(WifiP2pConfig::class.java.classLoader)!!
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(deviceAddress)
        parcel.writeParcelable(wpsInfo, flags)
        parcel.writeInt(groupownerIntent)
        parcel.writeInt(netId)
        parcel.writeString(networkName)
        parcel.writeString(passphrase)
        parcel.writeInt(groupOwnerBand)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<FakeWifiP2pConfig> {
        override fun createFromParcel(parcel: Parcel): FakeWifiP2pConfig {
            val deviceAddress = parcel.readString()
            val wpsInfo = parcel.readParcelable<WpsInfo>(WpsInfo::class.java.classLoader)
            val groupownerIntent = parcel.readInt()
            val netId = parcel.readInt()
            val networkName = parcel.readString()
            val passphrase = parcel.readString()
            val groupOwnerBand = parcel.readInt()
            return FakeWifiP2pConfig(
                    deviceAddress = deviceAddress,
                    wpsInfo = wpsInfo,
                    groupownerIntent = groupownerIntent,
                    netId = netId,
                    networkName = networkName,
                    passphrase = passphrase,
                    groupOwnerBand = groupOwnerBand
            )
        }

        override fun newArray(size: Int): Array<FakeWifiP2pConfig?> {
            return arrayOfNulls(size)
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

        const val NETWORK_ID_PERSISTENT = -2
    }

}