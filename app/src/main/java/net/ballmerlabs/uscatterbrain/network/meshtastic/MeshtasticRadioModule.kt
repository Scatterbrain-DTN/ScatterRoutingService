package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Completable

interface MeshtasticRadioModule {
    fun getSessionCount(): Int
    fun startSession(from: String): MeshtasticSessionSubcomponent
    fun startBacklog(from: String)
    fun popBacklog(): MeshtasticSessionSubcomponent?
    fun stopSession(from: String)
    fun handlePackets(): Completable
    fun handshake(): Completable
}