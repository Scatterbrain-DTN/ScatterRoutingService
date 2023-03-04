package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.os.Parcel
import android.os.Parcelable
import net.ballmerlabs.uscatterbrain.WifiDirectInfoScope
import net.ballmerlabs.uscatterbrain.WifiDirectInfoSubcomponent
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfig.Companion.GROUP_OWNER_BAND_AUTO
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfig.Companion.GROUP_OWNER_INTENT_AUTO
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfig.Companion.NETWORK_ID_PERSISTENT
import org.mockito.kotlin.mock
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
class MockFakeWifiP2pConfigImpl @Inject constructor(
        @Named(WifiDirectInfoSubcomponent.PASSPHRASE) val passphrase: String? = "",
        @Named(WifiDirectInfoSubcomponent.MAC_ADDRESS) val deviceAddress: String? = "02:00:00:00:00:00",
        @Named(WifiDirectInfoSubcomponent.NETWORK_NAME) val networkName: String? = "",
        val wpsInfo: WpsInfo?,
) : FakeWifiP2pConfig {



    var netId: Int = NETWORK_ID_PERSISTENT
    var groupOwnerBand: Int = GROUP_OWNER_BAND_AUTO
    var groupownerIntent: Int = GROUP_OWNER_INTENT_AUTO
    /**
     * Converts into vanilla WifiP2pConfig by parceling/unparceling
     * @return WifiP2pConfig
     */
    override fun asConfig(): WifiP2pConfig {
        return mock {  }
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
            val res = FakeWifiP2pConfigImpl(
                    deviceAddress = deviceAddress,
                    wpsInfo = wpsInfo,
                    networkName = networkName,
                    passphrase = passphrase,
                    suggestedband = FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ
            )
            res.groupownerIntent = groupownerIntent
            res.netId = netId
            res.groupOwnerBand = groupOwnerBand
            return res
        }

        override fun newArray(size: Int): Array<FakeWifiP2pConfig?> {
            return arrayOfNulls(size)
        }
    }

}