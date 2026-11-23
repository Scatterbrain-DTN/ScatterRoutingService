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
    fun handlePackets(): Completable
    fun handshake(): Completable
    fun connect(): Completable
    fun getConnection(): Flowable<ConnectionState>
}