package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Completable
import io.reactivex.Flowable
import org.meshtastic.sdk.ConnectionState

interface MeshtasticRadioModule {
    fun getSessionCount(): Int
    fun startSession(from: Int): MeshtasticSessionSubcomponent
    fun startBacklog(from: Int)
    fun popBacklog(): MeshtasticSessionSubcomponent?
    fun stopSession(from: Int)
    suspend fun handlePackets()
    suspend fun handshake()
    suspend fun connect()
    suspend fun getConnection(): Flowable<ConnectionState>
}