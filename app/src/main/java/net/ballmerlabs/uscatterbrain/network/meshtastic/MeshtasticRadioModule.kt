package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Completable

interface MeshtasticRadioModule {
    fun getSessionCount(): Int
    fun startSession(from: Int): MeshtasticSessionSubcomponent
    fun startBacklog(from: Int)
    fun popBacklog(): MeshtasticSessionSubcomponent?
    fun stopSession(from: Int)
    fun handlePackets(): Completable
    fun handshake(): Completable
}