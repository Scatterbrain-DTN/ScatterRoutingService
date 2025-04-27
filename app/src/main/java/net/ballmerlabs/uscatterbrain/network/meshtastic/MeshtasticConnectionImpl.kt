package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.content.Context
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.IMeshService
import com.geeksville.mesh.MeshUser
import com.geeksville.mesh.MyNodeInfo
import com.geeksville.mesh.NodeInfo
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.Single
import javax.inject.Inject
import javax.inject.Named

@MeshtasticConnectionScope
class MeshtasticConnectionImpl @Inject constructor(
    val service: IMeshService,
    val context: Context,
    @Named(MeshtasticConnectionSubcomponent.NamedSchedulers.BINDER_SCHEDULER) val scheduler: Scheduler
) : MeshtasticConnection {
    override fun subscribeReceiver() {
        service.subscribeReceiver(context.packageName, "net.ballmerlabs.scatterroutingservice")
    }

    override fun setOwner(user: MeshUser): Completable {
        return Completable.fromAction {
            service.setOwner(user)
        }.subscribeOn(scheduler)
    }

    override fun getMyId(): Single<String> {
        return Single.fromCallable {
            service.myId
        }.subscribeOn(scheduler)
    }

    override fun getPacketId(): Single<Int> {
        return Single.fromCallable {
            service.packetId
        }.subscribeOn(scheduler)
    }

    override fun send(packet: DataPacket): Completable {
        return Completable.fromAction {
            service.send(packet)
        }.subscribeOn(scheduler)
    }

    override fun getNodes(): Single<List<NodeInfo>> {
        return Single.fromCallable {
            service.nodes
        }.subscribeOn(scheduler)
    }

    override fun connectionState(): Single<String> {
        return Single.fromCallable {
            service.connectionState()
        }.subscribeOn(scheduler)
    }

    override fun getMyNodeInfo(): Single<MyNodeInfo> {
        return Single.fromCallable {
            service.myNodeInfo
        }.subscribeOn(scheduler)
    }

    override fun startProvideLocation(): Completable {
        return Completable.fromAction {
            service.startProvideLocation()
        }.subscribeOn(scheduler)
    }

    override fun stopProvideLocation(): Completable {
        return Completable.fromAction {
            service.stopProvideLocation()
        }.subscribeOn(scheduler)
    }
}