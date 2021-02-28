package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.util.Base64
import android.util.Log
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import java.util.*

class UpgradeStage(private val provides: AdvertisePacket.Provides) {
    private val metadata: MutableMap<String, String> = HashMap()
    val sessionID = Random(System.nanoTime()).nextInt()
    private fun initMetadata() {
        when (provides) {
            AdvertisePacket.Provides.WIFIP2P -> {
                metadata.putIfAbsent(WifiDirectBootstrapRequest.KEY_NAME, WifiDirectBootstrapRequest.DEFAULT_NAME)
                val pass = ByteArray(16)
                LibsodiumInterface.sodium.randombytes_buf(pass, pass.size)
                metadata.putIfAbsent(
                        WifiDirectBootstrapRequest.KEY_PASSPHRASE,
                        Base64.encodeToString(pass, Base64.NO_WRAP or Base64.NO_PADDING)
                )
            }
            else -> {
                Log.e(TAG, "initMetadata called with invalid provides")
            }
        }
    }

    val upgrade: Single<UpgradePacket>
        get() = when (provides) {
            AdvertisePacket.Provides.WIFIP2P -> {
                Single.fromCallable {
                    UpgradePacket.newBuilder()
                            .setProvides(provides)
                            .setMetadata(metadata)
                            .setSessionID(sessionID)
                            .build()
                }
            }
            else -> Single.error(IllegalStateException("unsupported provides"))
        }

    fun getMetadata(): Map<String, String> {
        return metadata
    }

    companion object {
        const val TAG = "UpgradeStage"
    }

    init {
        initMetadata()
    }
}