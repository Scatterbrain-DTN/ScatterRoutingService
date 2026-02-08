package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Completable
import io.reactivex.Flowable
import org.meshtastic.core.model.DataPacket

interface MeshtasticRadioModule {
    fun sendPacket(dataPacket: DataPacket): Completable
    fun handlePacket(dataPacket: DataPacket): Flowable<DataPacket>
    fun getSessionCount(): Int
    fun startSession(from: String): MeshtasticSessionSubcomponent
    fun startBacklog(from: String)
    fun popBacklog(): MeshtasticSessionSubcomponent?
    fun stopSession(from: String)
    fun handlePackets(): Completable
    fun handshake(): Completable
}