package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.DataPacket
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single

interface MeshtasticRadioModule {
    fun sendPacket(dataPacket: DataPacket): Completable
    fun handlePacket(dataPacket: DataPacket): Observable<DataPacket>
    fun getSessionCount(): Int
    fun startSession(from: String): MeshtasticSessionSubcomponent
    fun startBacklog(from: String)
    fun popBacklog(): MeshtasticSessionSubcomponent?
    fun stopSession(from: String)
    fun handlePackets(): Completable
    fun handshake(): Completable
}