package net.ballmerlabs.uscatterbrain.mock.meshtastic

import androidx.lifecycle.AtomicReference
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.MeshUser
import com.geeksville.mesh.MessageStatus
import com.geeksville.mesh.MyNodeInfo
import com.geeksville.mesh.NodeInfo
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.Completable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticBroadcastReceiverState
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnection
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionScope
import net.ballmerlabs.uscatterbrain.network.meshtastic.MessageStatusEvent
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named

@MeshtasticConnectionScope
class MockMeshtasticConnection @Inject constructor(
    @Named(FakeMeshtasticConnectionSubcomponent.REMOTE_STATE)
    val remoteState: MeshtasticBroadcastReceiverState,
    var broadcastReceiver: MeshBroadcastReceiver,
    var localState: MeshtasticBroadcastReceiverState
): MeshtasticConnection {



    val owner = AtomicReference<MeshUser?>(null)
    val id = AtomicReference<String>("test")
    val packetId = AtomicLong()

    override fun subscribeReceiver() {

    }

    override fun unsubscribeReceiver() {

    }

    override fun setOwner(user: MeshUser): Completable {
        return Completable.fromAction {
            owner.set(user)
        }
    }

    override fun getMyId(): Single<String> {
        return Single.fromCallable { id.get() }
    }

    override fun getPacketId(): Single<Int> {
        return Single.fromCallable {
            packetId.incrementAndGet().toInt()
        }
    }

    override fun send(packet: DataPacket): Completable {
        return Completable.fromAction {
            remoteState.acceptDataPacket(packet)
            localState.acceptMessageStatus(MessageStatusEvent(packet.id, MessageStatus.QUEUED))
            localState.acceptMessageStatus(MessageStatusEvent(packet.id, MessageStatus.ENROUTE))
            localState.acceptMessageStatus(MessageStatusEvent(packet.id, MessageStatus.DELIVERED))
        }
    }

    override fun getNodes(): Single<List<NodeInfo>> {
        return Single.fromCallable { listOf() }
    }

    override fun connectionState(): Single<String> {
        return Single.fromCallable { "CONNECTED" }
    }

    override fun getMyNodeInfo(): Single<MyNodeInfo> {
        return Single.fromCallable {
            MyNodeInfo(
                0,
                false,
                "test",
                "test",
                false,
                false,
                packetId.get(),
                0,
                0,
                16,
                false,
                0.0F,
                0.0F
                )
        }
    }

    override fun startProvideLocation(): Completable {
        return Completable.complete()
    }

    override fun stopProvideLocation(): Completable {
        return Completable.complete()
    }

}