package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.os.Parcel
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest

/**
 * BootstrapRequest with convenience functions for wifi direct bootstraps
 */
open class WifiDirectBootstrapRequest : BootstrapRequest {
    val name: String
    val passphrase: String
    val role: ConnectionRole

    //TODO: make this work with the JVM. currently can't serialize
    protected constructor(`in`: Parcel) : super(`in`) {
        name = getStringExtra(KEY_NAME)
        passphrase = getStringExtra(KEY_PASSPHRASE)
        role = getSerializableExtra(KEY_ROLE) as ConnectionRole
    }

    private constructor(passphrase: String, role: ConnectionRole) : super() {
        this.passphrase = passphrase
        name = "DIRECT-scatterbrain"
        this.role = role
        putStringExtra(KEY_NAME, name)
        putStringExtra(KEY_PASSPHRASE, passphrase)
        putSerializableExtra(KEY_ROLE, role)
    }

    private constructor(passphrase: String, name: String, role: ConnectionRole) : super() {
        this.passphrase = passphrase
        this.name = name
        this.role = role
        putStringExtra(KEY_NAME, name)
        putStringExtra(KEY_PASSPHRASE, passphrase)
        putSerializableExtra(KEY_ROLE, role)
    }

    companion object {
        const val DEFAULT_NAME = "DIRECT-scatterbrain"
        const val KEY_NAME = "p2p-groupname"
        const val KEY_PASSPHRASE = "p2p-passphrase"
        const val KEY_ROLE = "p2p-role"
        fun create(passphrase: String, role: ConnectionRole): WifiDirectBootstrapRequest {
            return WifiDirectBootstrapRequest(passphrase, role)
        }

        fun create(packet: UpgradePacket, role: ConnectionRole): WifiDirectBootstrapRequest {
            check(packet.provides == AdvertisePacket.Provides.WIFIP2P) {
                "WifiDirectBootstrapRequest called with invalid provides: ${packet.provides}"
            }
            val name = packet.metadata!![KEY_NAME]
                    ?: throw IllegalArgumentException("name was null")
            val passphrase = packet.metadata[KEY_PASSPHRASE]
                    ?: throw IllegalArgumentException("passphrase was null")
            return WifiDirectBootstrapRequest(passphrase, name, role)
        }
    }
}