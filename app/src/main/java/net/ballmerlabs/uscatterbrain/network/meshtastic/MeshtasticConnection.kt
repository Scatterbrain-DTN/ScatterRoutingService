package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.MeshUser
import com.geeksville.mesh.MyNodeInfo
import com.geeksville.mesh.NodeInfo
import io.reactivex.Completable
import io.reactivex.Single

interface MeshtasticConnection {
    fun subscribeReceiver()
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