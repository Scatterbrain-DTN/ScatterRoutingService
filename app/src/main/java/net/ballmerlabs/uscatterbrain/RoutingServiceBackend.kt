package net.ballmerlabs.uscatterbrain

import io.reactivex.Completable
import io.reactivex.Single
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler

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

    fun sendAndSignMessage(message: ScatterMessage, identity: String, callingPackageName: String): Completable
    fun sendAndSignMessages(messages: List<ScatterMessage>, identity: String, callingPackageName: String): Completable
    fun generateIdentity(name: String, callingPackageName: String): Single<Identity>
    fun authorizeApp(fingerprint: String, packageName: String): Completable
    fun deauthorizeApp(fingerprint: String, packageName: String): Completable
    fun removeIdentity(name: String, callingPackageName: String): Completable
    fun sendMessage(message: ScatterMessage): Completable
    fun sendMessages(messages: List<ScatterMessage>): Completable
    fun signDataDetached(data: ByteArray, identity: String, callingPackageName: String): Single<ByteArray>
}