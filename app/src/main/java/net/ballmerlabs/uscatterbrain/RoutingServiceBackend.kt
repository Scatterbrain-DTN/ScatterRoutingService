package net.ballmerlabs.uscatterbrain

import io.reactivex.Completable
import io.reactivex.Single
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import java.util.*

/**
 * Dagger2 interface for RoutingServiceBackend
 */
interface RoutingServiceBackend {
    object Applications {
        const val APPLICATION_FILESHARING = "fileshare"
    }
    val radioModule: BluetoothLEModule
    val wifiDirect: WifiDirectRadioModule
    val datastore: ScatterbrainDatastore
    val scheduler: ScatterbrainScheduler
    val prefs: RouterPreferences

    companion object {
        const val DEFAULT_TRANSACTIONTIMEOUT: Long = 120
    }

    fun sendAndSignMessage(message: ScatterMessage, identity: UUID, callingPackageName: String): Completable
    fun sendAndSignMessages(messages: List<ScatterMessage>, identity: UUID, callingPackageName: String): Completable
    fun generateIdentity(name: String, callingPackageName: String): Single<Identity>
    fun authorizeApp(fingerprint: UUID, packageName: String): Completable
    fun deauthorizeApp(fingerprint: UUID, packageName: String): Completable
    fun removeIdentity(name: UUID, callingPackageName: String): Completable
    fun sendMessage(message: ScatterMessage, callingPackageName: String): Completable
    fun sendMessages(messages: List<ScatterMessage>, callingPackageName: String): Completable
    fun signDataDetached(data: ByteArray, identity: UUID, callingPackageName: String): Single<ByteArray>
    fun getIdentity(fingerprint: UUID): Single<Identity>
}