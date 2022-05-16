package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.os.Parcel
import android.os.Parcelable
import net.ballmerlabs.uscatterbrain.WifiDirectInfoScope
import net.ballmerlabs.uscatterbrain.WifiDirectInfoSubcomponent
import javax.inject.Inject
import javax.inject.Named

fun getWpsInfo(): WpsInfo {
    return WpsInfo()
}

/**
 * Incredibly hacky kludge to allow creating a wifi direct group with a custom
 * name/passphrase on pre-api29 devices. This works by constructing a WifiP2pConfig object
 * using a crafted parcelable stream, which avoids running afoul of Google's ban on calling
 * hidden/private apis
 *
 * @constructor constructs a FakeWifiP2pConfig object
 * @property passphrase desired passphrase
 * @property deviceAddress address of remote device to connect to
 * @property networkName name of wifidirect network to connect to
 * @property wpsInfo internal use
 *
 */
@WifiDirectInfoScope
class FakeWifiP2pConfigImpl @Inject constructor(
        @Named(WifiDirectInfoSubcomponent.PASSPHRASE) val passphrase: String? = "",
        @Named(WifiDirectInfoSubcomponent.MAC_ADDRESS) val deviceAddress: String? = "02:00:00:00:00:00",
        @Named(WifiDirectInfoSubcomponent.NETWORK_NAME) val networkName: String? = "",
        val wpsInfo: WpsInfo?,
) : Parcelable {



    var netId: Int = NETWORK_ID_PERSISTENT
    var groupOwnerBand: Int = GROUP_OWNER_BAND_AUTO
    var groupownerIntent: Int = GROUP_OWNER_INTENT_AUTO
    /**
     * Converts into vanilla WifiP2pConfig by parceling/unparceling
     * @return WifiP2pConfig
     */
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

    companion object CREATOR : Parcelable.Creator<FakeWifiP2pConfigImpl> {
        override fun createFromParcel(parcel: Parcel): FakeWifiP2pConfigImpl {
            val deviceAddress = parcel.readString()
            val wpsInfo = parcel.readParcelable<WpsInfo>(WpsInfo::class.java.classLoader)
            val groupownerIntent = parcel.readInt()
            val netId = parcel.readInt()
            val networkName = parcel.readString()
            val passphrase = parcel.readString()
            val groupOwnerBand = parcel.readInt()
            val res = FakeWifiP2pConfigImpl(
                    deviceAddress = deviceAddress,
                    wpsInfo = wpsInfo,
                    networkName = networkName,
                    passphrase = passphrase,
            )
            res.groupownerIntent = groupownerIntent
            res.netId = netId
            res.groupOwnerBand = groupOwnerBand
            return res
        }

        override fun newArray(size: Int): Array<FakeWifiP2pConfigImpl?> {
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