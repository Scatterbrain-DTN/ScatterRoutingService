package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.os.Bundle
import android.os.Parcel
import android.os.ParcelUuid
import net.ballmerlabs.scatterproto.Provides
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket.Companion.FROM
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket.Companion.KEY_BAND
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket.Companion.KEY_NAME
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket.Companion.KEY_OWNER_ADDRESSS
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket.Companion.KEY_PASSPHRASE
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket.Companion.KEY_PORT
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket.Companion.KEY_ROLE
import net.ballmerlabs.uscatterbrain.BootstrapRequestScope
import net.ballmerlabs.uscatterbrain.BootstrapRequestSubcomponent

import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import proto.Scatterbrain
import java.net.InetAddress
import java.util.UUID
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
    val from: UUID
    val passphrase: String
    val role: BluetoothLEModule.Role
    val band: Int
    val port: Int
    val groupOwnerAdress: InetAddress

    //TODO: make this work with the JVM. currently can't serialize
    protected constructor(`in`: Parcel) : super(`in`) {
        name = getStringExtra(KEY_NAME)
        passphrase = getStringExtra(KEY_PASSPHRASE)
        role = getSerializableExtra(KEY_ROLE) as BluetoothLEModule.Role
        band = getStringExtra(KEY_BAND).toInt()
        port = getStringExtra(KEY_PORT).toInt()
        groupOwnerAdress = InetAddress.getByName(getStringExtra(KEY_OWNER_ADDRESSS))
        from = getParcelableExtra<ParcelUuid>(FROM).uuid
    }

    /**
     * Converts this BootstrapRequest into a Scatterbrain upgrade packet
     * @param session session id for the upgrade transaction
     * @return UpgradePacket
     */
    fun toUpgrade(session: Int): UpgradePacket {
        return UpgradePacket.newBuilder(role.toProto())
            .setProvides(Provides.WIFIP2P)
            .setSessionID(session)
            .setFrom(from)
            .setMetadata(HashMap<String, String>().apply {
                put(KEY_NAME, name)
                put(KEY_PASSPHRASE, passphrase)
                put(KEY_BAND, band.toString())
                put(KEY_PORT, port.toString())
                put(KEY_OWNER_ADDRESSS, groupOwnerAdress.hostAddress!!)
                put(FROM, from.toString())
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
        @Named(BootstrapRequestSubcomponent.OWNER_ADDRESS) ownerAddress: InetAddress,
        from: UUID
    ) : super() {
        this.extras = extras
        this.passphrase = passphrase
        this.name = name
        this.role = role
        this.band = band
        this.port = port
        this.groupOwnerAdress = ownerAddress
        this.from = from
        putStringExtra(KEY_NAME, name)
        putStringExtra(KEY_PASSPHRASE, passphrase)
        putSerializableExtra(KEY_ROLE, role)
        putStringExtra(KEY_BAND, band.toString())
        putStringExtra(KEY_PORT, port.toString())
        putStringExtra(KEY_OWNER_ADDRESSS, ownerAddress.hostAddress!!)
        putParcelableExtra(FROM, ParcelUuid(from))
    }

    companion object {
     

        /**
         * creates a WifiDirectBootstrapRequest
         * @param packet Scatterbrain upgrade packet
         * @param role connection role (uke/seme) of the network to connect to
         * @return WifiDirectBootstrapRequest
         */
        fun create(
            packet: UpgradePacket,
            builder: BootstrapRequestSubcomponent.Builder,
        ): WifiDirectBootstrapRequest {
            check(packet.provides == Provides.WIFIP2P) {
                "WifiDirectBootstrapRequest called with invalid provides: ${packet.provides}"
            }
            val name = packet.metadata[KEY_NAME]
                ?: throw IllegalArgumentException("name was null")
            val passphrase = packet.metadata[KEY_PASSPHRASE]
                ?: throw IllegalArgumentException("passphrase was null")

            val role = when(packet.role) {
                Scatterbrain.Role.UKE -> BluetoothLEModule.Role.ROLE_UKE
                Scatterbrain.Role.SUPER_UKE -> BluetoothLEModule.Role.ROLE_SUPERUKE
                Scatterbrain.Role.SUPER_SEME -> BluetoothLEModule.Role.ROLE_SUPERSEME
                Scatterbrain.Role.SEME -> BluetoothLEModule.Role.ROLE_SEME
                else -> throw IllegalArgumentException("invalid role ${packet.role}")
            }

            val band = packet.metadata[KEY_BAND]?.toInt()
                ?: throw IllegalArgumentException("band was null")
            val port = packet.metadata[KEY_PORT]
                ?: throw IllegalArgumentException("port was null")

            val ownerAddress = packet.metadata[KEY_OWNER_ADDRESSS]
                ?: throw IllegalArgumentException("ownerAddress was null")
            val from = UUID.fromString(packet.metadata[FROM])
            return builder.wifiDirectArgs(
                BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs(
                    name = name,
                    passphrase = passphrase,
                    role = role,
                    band = band,
                    port = port.toInt(),
                    ownerAddress = InetAddress.getByName(ownerAddress),
                    from = from
                )
            ).build()!!.wifiBootstrapRequest()
        }
    }
}