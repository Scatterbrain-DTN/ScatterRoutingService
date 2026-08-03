package net.ballmerlabs.uscatterbrain.mock.meshtastic

import io.reactivex.Completable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticBroadcastReceiverState
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnection
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionScope
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named

@MeshtasticConnectionScope
class MockMeshtasticConnection @Inject constructor(
    @Named(FakeMeshtasticConnectionSubcomponent.REMOTE_STATE)
    val remoteState: MeshtasticBroadcastReceiverState,
    var broadcastReceiver: MeshBroadcastReceiver,
    var localState: MeshtasticBroadcastReceiverState,
    @Named(FakeMeshtasticConnectionSubcomponent.MY_ID) val myId: String
): MeshtasticConnection {

    private val log by scatterLog()

    val id = AtomicReference(myId)
    val packetId = AtomicLong()

    override fun subscribeReceiver() {

    }

    override fun unsubscribeReceiver() {

    }

}