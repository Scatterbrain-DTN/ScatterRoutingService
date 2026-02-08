package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Completable
import io.reactivex.Single
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.MeshUser
import org.meshtastic.core.model.MyNodeInfo
import org.meshtastic.core.model.NodeInfo

interface MeshtasticConnection {
    fun subscribeReceiver()
    fun unsubscribeReceiver()
    fun setOwner(user: MeshUser): Completable
    fun getMyId(): Single<String>
    fun getPacketId(): Single<Int>
    fun send(packet: DataPacket): Completable
    fun getNodes(): Single<List<NodeInfo>>
    fun connectionState(): Single<String>
    fun getMyNodeInfo(): Single<MyNodeInfo>
    fun startProvideLocation(): Completable
    fun stopProvideLocation(): Completable
}