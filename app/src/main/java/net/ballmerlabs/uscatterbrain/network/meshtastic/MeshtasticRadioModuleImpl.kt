package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.icu.util.TimeUnit
import androidx.room.Database
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.MessageStatus
import io.reactivex.Completable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@MeshtasticConnectionScope
class MeshtasticRadioModuleImpl @Inject constructor(
    val datastore: ScatterbrainDatastore,
    val merkle: Database,
    val connection: MeshtasticConnection,
    val broadcastReceiver: MeshtasticBroadcastReceiverState,
    val sessionBuilder: MeshtasticSessionSubcomponent.Builder
) : MeshtasticRadioModule {

    private val currentTransactions = ConcurrentHashMap<String, MeshtasticSessionSubcomponent>()

    fun handlePacket(dataPacket: DataPacket): Completable {
        return Completable.complete()
    }

    fun sendPacket(dataPacket: DataPacket): Completable {
        return connection.getPacketId().flatMapCompletable { id ->
            connection.send(dataPacket.apply {
                this.id = id
                channel = PORT_NUMBER
                wantAck = false
            })
                .andThen(
                    broadcastReceiver.onMessageStatus()
                        .takeUntil { s ->
                            s.packetId == id &&
                                    (s.messageStatus == MessageStatus.DELIVERED ||
                                                    s.messageStatus == MessageStatus.ERROR)
                        }.flatMapCompletable { v ->
                            when (v.messageStatus) {
                                MessageStatus.ERROR -> Completable.error(IllegalStateException("message send err"))
                                MessageStatus.DELIVERED -> Completable.complete()
                                else -> Completable.error(IllegalStateException("this should never happen"))
                            }
                        }
                )
        }

    }


    fun broadcastPeers(): Single<List<String>> {
        TODO()
    }
}