package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.DataPacket
import io.reactivex.Completable

interface MeshtasticRadioModule {
    fun sendPacket(dataPacket: DataPacket): Completable
    fun handlePacket(dataPacket: DataPacket): Completable
    fun getSessionCount(): Int
    fun startSession(from: String): MeshtasticSessionSubcomponent
    fun startBacklog(from: String)
    fun popBacklog(): MeshtasticSessionSubcomponent?
    fun stopSession(from: String)
}