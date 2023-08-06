package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.os.Bundle
import android.os.Parcel
import net.ballmerlabs.uscatterbrain.BootstrapRequestScope
import net.ballmerlabs.uscatterbrain.BootstrapRequestSubcomponent
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import javax.inject.Inject
import javax.inject.Named


/**
 * BootstrapRequest with convenience functions for wifi direct bootstraps
 * @property name name of the wifi direct network to connect to
 * @property passphrase passphrase of the wifi direct network to connect to
 * @property role role (uke/seme) of the connection session
 *
 */
@BootstrapRequestScope
open class WifiDirectBootstrapRequest : BootstrapRequest {
    val name: String
    val passphrase: String
    val role: BluetoothLEModule.Role
    val band: Int
    val port: Int

    //TODO: make this work with the JVM. currently can't serialize
    protected constructor(`in`: Parcel) : super(`in`) {
        name = getStringExtra(KEY_NAME)
        passphrase = getStringExtra(KEY_PASSPHRASE)
        role = getSerializableExtra(KEY_ROLE) as BluetoothLEModule.Role
        band = getStringExtra(KEY_BAND).toInt()
        port = getStringExtra(KEY_PORT).toInt()
    }

    /**
     * Converts this BootstrapRequest into a Scatterbrain upgrade packet
     * @param session session id for the upgrade transaction
     * @return UpgradePacket
     */
    fun toUpgrade(session: Int): UpgradePacket {
        return UpgradePacket.newBuilder()
            .setProvides(AdvertisePacket.Provides.WIFIP2P)
            .setSessionID(session)
            .setMetadata(HashMap<String, String>().apply {
                put(KEY_NAME, name)
                put(KEY_PASSPHRASE, passphrase)
                put(KEY_BAND, band.toString())
                put(KEY_PORT, port.toString())
            })
            .build()!!
    }

    @Inject
    protected constructor(
        @Named(BootstrapRequestSubcomponent.PASSPHRASE) passphrase: String,
        @Named(BootstrapRequestSubcomponent.NAME) name: String,
        role: BluetoothLEModule.Role,
        extras: Bundle,
        @Named(BootstrapRequestSubcomponent.BAND) band: Int,
        @Named(BootstrapRequestSubcomponent.PORT) port: Int,
    ) : super() {
        this.extras = extras
        this.passphrase = passphrase
        this.name = name
        this.role = role
        this.band = band
        this.port = port
        putStringExtra(KEY_NAME, name)
        putStringExtra(KEY_PASSPHRASE, passphrase)
        putSerializableExtra(KEY_ROLE, role)
        putStringExtra(KEY_BAND, band.toString())
        putStringExtra(KEY_PORT, port.toString())
    }

    companion object {
        const val KEY_NAME = "p2p-groupname"
        const val KEY_PASSPHRASE = "p2p-passphrase"
        const val KEY_ROLE = "p2p-role"
        const val KEY_BAND = "p2p-band"
        const val KEY_PORT = "p2p-port"

        /**
         * creates a WifiDirectBootstrapRequest
         * @param packet Scatterbrain upgrade packet
         * @param role connection role (uke/seme) of the network to connect to
         * @return WifiDirectBootstrapRequest
         */
        fun create(
            packet: UpgradePacket,
            role: BluetoothLEModule.Role,
            builder: BootstrapRequestSubcomponent.Builder,
            band: Int
        ): WifiDirectBootstrapRequest {
            check(packet.provides == AdvertisePacket.Provides.WIFIP2P) {
                "WifiDirectBootstrapRequest called with invalid provides: ${packet.provides}"
            }
            val name = packet.metadata[KEY_NAME]
                ?: throw IllegalArgumentException("name was null")
            val passphrase = packet.metadata[KEY_PASSPHRASE]
                ?: throw IllegalArgumentException("passphrase was null")

            val port = packet.metadata[KEY_PORT]
                ?: throw IllegalArgumentException("port was null")
            return builder.wifiDirectArgs(
                BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs(
                    name = name,
                    passphrase = passphrase,
                    role = role,
                    band = band,
                    port = port.toInt()
                )
            ).build()!!.wifiBootstrapRequest()
        }
    }
}