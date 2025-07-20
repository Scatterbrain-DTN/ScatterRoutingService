package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.MessageStatus
import io.ktor.util.encodeBase64
import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticAnnouncePacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.reply
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.toBroadcast
import net.ballmerlabs.uscatterbrain.util.retryDelay
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

@MeshtasticConnectionScope
class MeshtasticRadioModuleImpl @Inject constructor(
    val datastore: ScatterbrainDatastore,
    val connection: MeshtasticConnection,
    val broadcastReceiver: MeshtasticBroadcastReceiverState,
    val sessionBuilder: MeshtasticSessionSubcomponent.Builder,
    val advertiser: Advertiser,
    val database: Datastore,
    @Named(RoutingServiceComponent.NamedSchedulers.TIMEOUT) val timeoutScheduler: Scheduler
) : MeshtasticRadioModule {


    private val log by scatterLog()


    private val currentTransactions = ConcurrentHashMap<String, MeshtasticSessionSubcomponent>()
    private val backlog = ConcurrentLinkedQueue<MeshtasticSessionSubcomponent>()

    override fun popBacklog(): MeshtasticSessionSubcomponent? {
        return backlog.poll()
    }

    override fun startBacklog(from: String) {
        val session = currentTransactions.compute(from) { k, v ->
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

        log.v("startSession size=${currentTransactions.size} from=$from")

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

    override fun handlePacket(dataPacket: DataPacket): Flowable<DataPacket> {
        return connection.getMyId().flatMapPublisher { myID ->
            val from = dataPacket.from

            log.v("handlePacket from=$from my=$myID" )

            if (from != myID) {
                if (from != null)
                    startSession(myID).state().handlePacket(dataPacket)
                        .map { v ->
                              dataPacket.reply(v, dataPacket.id, dataPacket.from)
                        }
                else
                    Flowable.empty<DataPacket>()
                        .doOnComplete { log.v("got packet without from") }
            } else {
                log.w("got connection from self??")
                Flowable.empty()
            }
        }
    }

    override fun sendPacket(dataPacket: DataPacket): Completable {
        return connection.getPacketId().flatMapCompletable { id ->
            connection.getMyId().flatMapCompletable { myId ->
                log.v("sendPacket with id $id")
                broadcastReceiver.onMessageStatus()
                    .filter { v -> v.packetId == id }
                    .flatMap { v ->
                        log.v("sendPacket message status ${v.messageStatus}")
                        when (v.messageStatus) {
                            MessageStatus.ERROR -> Flowable.error(IllegalStateException("message send err"))
                            else -> Flowable.just(v)
                        }
                    }.takeUntil { v -> v.messageStatus == MessageStatus.DELIVERED || v.messageStatus == MessageStatus.RECEIVED }
                    .ignoreElements()
                    .mergeWith(
                        connection.send(dataPacket.apply {
                            this.id = id
                            from = myId
                        }).doOnError { err -> log.e("failed to sendPacket: $err") }
                            .doOnComplete { log.v("initiated sendPacket") }
                            .onErrorComplete())
            }.doOnComplete {
                log.v("sendPacket complete")
            }
        }.timeout(45, TimeUnit.SECONDS, timeoutScheduler)
            .retryDelay(5, 1)
    }

    override fun handlePackets(): Completable {
        return broadcastReceiver.onDataPacket()
            .flatMap { p ->
                log.v("packet? ${p.dataType} ${p.from}")
                handlePacket(p)
            }.concatMapCompletable { v ->
                sendPacket(v)
            }
            .doOnSubscribe { log.v("handlePackets subscribed") }
    }

    override fun handshake(): Completable {
        log.w("explicit handshake!")
        return datastore.rehashMerkle().andThen(datastore.getDefaultMerkleRoot())
            .flatMapCompletable { root ->
            log.v("default root ${root.size}")
            connection.getMyId()
                .toMaybe()
                .onErrorComplete()
                .flatMapCompletable { routerId ->
                log.v("initiate handshake with root me=$routerId")
                sendPacket(
                    MeshtasticAnnouncePacket(advertiser.getHashLuid(), root)
                        .toBroadcast(from = routerId)
                )
                    .doFinally { log.v("sendPacket complete") }
            }
                .doFinally { log.v("handshake complete") }
        }

    }
}