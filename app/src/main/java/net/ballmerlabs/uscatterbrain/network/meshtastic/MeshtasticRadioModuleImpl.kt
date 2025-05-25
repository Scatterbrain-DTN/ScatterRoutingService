package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.MessageStatus
import io.reactivex.Completable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.reply
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

@MeshtasticConnectionScope
class MeshtasticRadioModuleImpl @Inject constructor(
    val datastore: ScatterbrainDatastore,
    val connection: MeshtasticConnection,
    val broadcastReceiver: MeshtasticBroadcastReceiverState,
    val sessionBuilder: MeshtasticSessionSubcomponent.Builder,
    val advertiser: Advertiser,
    val database: Datastore,
) : MeshtasticRadioModule {

    private val currentTransactions = ConcurrentHashMap<String, MeshtasticSessionSubcomponent>()
    private val backlog = ConcurrentLinkedQueue<MeshtasticSessionSubcomponent>()

    override fun popBacklog(): MeshtasticSessionSubcomponent? {
        return backlog.poll()
    }

    override fun startBacklog(from: String) {
        val session =  currentTransactions.compute(from) { k, v ->
            when (v) {
                null -> sessionBuilder.id(k).build()
                else -> v
            }
        }!!

        backlog.add(session)
    }

    override fun startSession(from: String): MeshtasticSessionSubcomponent {
            val session = currentTransactions.compute(from) { k, v ->
                when (v) {
                    null -> sessionBuilder.id(k).build()
                    else -> v
                }
            }!!

            return if (currentTransactions.size > MAX_SESSIONS) {
                backlog.add(session)
                session
            } else {
                session
            }
    }

    override fun stopSession(from: String) {
        currentTransactions.remove(from)
    }

    override fun getSessionCount(): Int {
        return currentTransactions.size
    }

    override fun handlePacket(dataPacket: DataPacket): Completable {
        return Completable.defer {
            val from = dataPacket.from
            if (from != null)
                startSession(from).state().handlePacket(dataPacket)
                    .flatMapCompletable { v -> sendPacket(dataPacket.reply(v)) }
            else
                Completable.complete()
        }
    }

    override fun sendPacket(dataPacket: DataPacket): Completable {
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