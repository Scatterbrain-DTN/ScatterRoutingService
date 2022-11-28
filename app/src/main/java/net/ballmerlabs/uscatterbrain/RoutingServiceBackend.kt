package net.ballmerlabs.uscatterbrain

import io.reactivex.Completable
import io.reactivex.Single
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeState
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeStateImpl
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import java.util.*

/**
 * Dagger2 interface for RoutingServiceBackend
 */
interface RoutingServiceBackend {
    object Applications {
        const val APPLICATION_FILESHARING = "fileshare"
    }
    val datastore: ScatterbrainDatastore
    val scheduler: ScatterbrainScheduler
    val prefs: RouterPreferences
    val leState: LeState

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
    fun verifyData(data: ByteArray, sig: ByteArray, identity: UUID): Single<Boolean>
    fun getIdentity(fingerprint: UUID): Single<Identity>
    fun registerReceiver()
    fun unregisterReceiver()
    fun refreshPeers(): Completable
}