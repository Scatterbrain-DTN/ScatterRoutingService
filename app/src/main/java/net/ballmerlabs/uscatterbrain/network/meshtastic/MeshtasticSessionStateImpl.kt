package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.DataPacket
import io.ktor.util.encodeBase64
import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import io.reactivex.subjects.ReplaySubject
import io.reactivex.subjects.SingleSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.verifyed25519
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.TransactionResult
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticAnnounceAckPacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticAnnouncePacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticAnnounceSynAckPacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticErrPacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticMerklePacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticMerklePacketParser
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticStreamPacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticStreamPacketParser
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.MeshtasticPacketStream
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.fromMeshtastic
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.toBroadcast
import net.ballmerlabs.uscatterbrain.network.proto.BlockHeaderPacketParser
import net.ballmerlabs.uscatterbrain.network.proto.BlockSequencePacket
import net.ballmerlabs.uscatterbrain.network.proto.BlockSequencePacketParser
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.util.concatMapLast
import net.ballmerlabs.uscatterbrain.util.enumerateMap
import net.ballmerlabs.uscatterbrain.util.scatterLog
import proto.Scatterbrain
import proto.Scatterbrain.MeshtasticAckCode
import proto.Scatterbrain.MeshtasticMerkle
import proto.Scatterbrain.MeshtasticStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named

@MeshtasticSessionScope
class MeshtasticSessionStateImpl @Inject constructor(
    val connection: MeshtasticConnection,
    val broadcastReceiver: MeshtasticBroadcastReceiverState,
    val datastore: ScatterbrainDatastore,
    val radioModule: MeshtasticRadioModule,
    val advertiser: Advertiser,
    @Named(MeshtasticSessionSubcomponent.PARSE_SCHEDULER) val parseScheduler: Scheduler,
    @Named(MeshtasticSessionSubcomponent.ROUTER_ID) val routerId: String,
) : MeshtasticSessionState {

    private val log by scatterLog()

    val stage = AtomicReference(Stage.ANNOUNCE)
    var remoteLuid: UUID? = null
    val currentMerkleStream =
        AtomicReference(
            MeshtasticPacketStream(MeshtasticMerklePacketParser.parser)
        )
    val currentDataStream = AtomicReference(
        MeshtasticPacketStream(MeshtasticStreamPacketParser.parser)
    )
    private val handshake = AtomicReference(CompletableSubject.create())

    override fun <R> mapStageSingle(onSuccess: Stage, func: (Stage) -> Single<R>): Single<R> {
        return Single.fromCallable {
            stage.getAndSet(Stage.LOCKED)
        }.flatMap { s ->
            func(s)
                .doOnSuccess { stage.set(onSuccess) }
        }

    }

    override fun <R> mapStagePublisher(
        onSuccess: Stage,
        func: (Stage) -> Flowable<R>
    ): Flowable<R> {
        return Single.fromCallable {
            stage.getAndSet(Stage.LOCKED)
        }.flatMapPublisher { s ->
            func(s)
                .doOnComplete { stage.set(onSuccess) }
        }
    }

    override fun <R> mapStageMaybe(onSuccess: Stage, func: (Stage) -> Maybe<R>): Maybe<R> {
        return Single.fromCallable {
            stage.getAndSet(Stage.LOCKED)
        }.flatMapMaybe { s ->
            func(s).doOnSuccess { stage.set(onSuccess) }
        }
    }

    override fun <R> mapStageObservable(
        onSuccess: Stage,
        func: (Stage) -> Observable<R>,
    ): Observable<R> {
        return Single.fromCallable {
            stage.getAndSet(Stage.LOCKED)
        }.flatMapObservable { s ->
            func(s)
                .doOnComplete { stage.set(onSuccess) }
        }
    }

    override fun mapStageCompletable(onSuccess: Stage, func: (Stage) -> Completable): Completable {
        return Single.fromCallable {
            stage.getAndSet(Stage.LOCKED)
        }.flatMapCompletable { s ->
            func(s)
                .doOnComplete { stage.set(onSuccess) }
        }
    }

    override fun mapStage(func: (Stage) -> Single<Stage>): Single<Stage> {
        return Single.defer {
            val new = stage.getAndSet(Stage.LOCKED)
            if (new == Stage.LOCKED) {
                return@defer Single.error(ConcurrentModificationException())
            }
            func(new)
                .flatMap { v ->
                    when (v) {
                        Stage.LOCKED -> Single.error(
                            ConcurrentModificationException()
                        )

                        else -> Single.just(v)
                    }
                }
                .doOnSuccess { v -> stage.set(v) }
        }
    }

    private fun handleAnnouncePacket(packet: MeshtasticAnnouncePacket, to: String?): Flowable<ScatterSerializable<*>> {
        if (remoteLuid == null)
            remoteLuid = packet.remoteLuid
        return mapStagePublisher(Stage.ACK) { s ->
            log.v("handleAnnouncePacket id=$routerId")
            datastore.getDefaultMerkleRoot().flatMapPublisher { root ->
                val code = if (radioModule.getSessionCount() > MAX_SESSIONS) {
                    radioModule.startBacklog(routerId)
                    MeshtasticAckCode.FULL
                } else {
                    MeshtasticAckCode.TRANSACTION
                }

                when(to) {
                    DataPacket.ID_BROADCAST -> Flowable.just(
                        MeshtasticAnnouncePacket(
                            advertiser.getHashLuid(),
                            root
                        )
                    ).doOnSubscribe { log.v("replying to broadcast me=$routerId") }
                    routerId -> Flowable.fromIterable(
                        listOf(
                            MeshtasticAnnouncePacket(
                                advertiser.getHashLuid(),
                                root
                            ),
                            MeshtasticAnnounceAckPacket(
                            advertiser.getHashLuid(),
                            root,
                            code
                        ))
                    ).doOnSubscribe { log.v("replying to unicast me=$routerId") }
                    null -> Flowable.error(IllegalStateException("packet with null to field"))
                    else -> Flowable.error(IllegalStateException("packet with invalid to field $to my=$routerId"))
                }

            }
        }
    }

    private fun handleAnnounceAckPacket(packet: MeshtasticAnnounceAckPacket): Flowable<ScatterSerializable<*>> {
        return mapStagePublisher(Stage.SYNACK) { s ->
            log.v("handleAnnounceAckPacket id=$routerId")
            when (packet.code) {
                MeshtasticAckCode.FULL -> {
                    radioModule.startBacklog(routerId)
                    Flowable.empty()
                }

                else -> {
                    val code = if (radioModule.getSessionCount() > MAX_SESSIONS) {
                        radioModule.startBacklog(routerId)
                        MeshtasticAckCode.FULL
                    } else {
                        MeshtasticAckCode.TRANSACTION
                    }

                    Flowable.just(MeshtasticAnnounceSynAckPacket(code))
                }
            }
        }
    }

    private fun handleAnnounceSynAckPacket(packet: MeshtasticAnnounceSynAckPacket): Flowable<ScatterSerializable<*>> {
        return mapStagePublisher(Stage.MERKLE) { s ->
            log.v("handleAnnounceSynAckPacket id=$routerId code=${packet.code}")
            when (packet.code) {
                MeshtasticAckCode.FULL -> Flowable.empty()
                MeshtasticAckCode.WAIT -> Flowable.empty()
                else -> datastore.getDefaultMerkleRoot().flatMapPublisher { root ->

                    val stream = currentMerkleStream.updateAndGet { s ->
                        when(s) {
                            null -> MeshtasticPacketStream(MeshtasticMerklePacketParser.parser)
                            else -> s
                        }
                    }!!
                    val datastream = currentDataStream.updateAndGet { s ->
                        when(s) {
                            null -> MeshtasticPacketStream(MeshtasticStreamPacketParser.parser)
                            else -> s
                        }
                    }!!
                    datastore.getMerkleHubs(
                        stream.toFlowable(BackpressureStrategy.BUFFER)
                            .flatMap { h ->
                                Flowable.fromIterable(h.hashes)
                            }
                    ).flatMapPublisher { hubresponse ->
                        val obs: Flowable<ScatterSerializable<*>>  =
                            hubresponse.exclude.toList().flatMapObservable { hashes ->
                                log.w("got merkle hash list ${hashes.size}")
                                datastore.getTopRandomMessages(50, hashes)
                                    .doOnNext { v -> log.v("sending stream packet end=${v.headerPacket.isEndOfStream}") }
                                    .map { v -> v }

                            }.flatMap { p ->
                                MeshtasticStreamPacket.fromStream(p).toObservable()
                            }.concatMapLast { v ->
                                MeshtasticStreamPacket(seq = v.seq, body = v.payload, end = true)
                            }.toFlowable(BackpressureStrategy.BUFFER)
                                .map { v -> v as ScatterSerializable<*> }
                                .doFinally { log.v("obs completed") }



                        val resp = hubresponse.hubs
                            .buffer(MeshtasticMerklePacket.MESHTASTIC_MAX_HASHES.toInt())
                            .enumerateMap { hub, seq ->
                                MeshtasticMerklePacket(
                                    seq = seq,
                                    hashes = hub.filter { v -> v.hash  != null }
                                        .map { v -> v.hash!! }
                                )
                            }.concatMapLast { v ->
                                MeshtasticMerklePacket(
                                    end = true,
                                    seq = v.seq,
                                    hashes = v.hashes
                                )
                            }
                            .doOnNext { v -> log.v("sending merkle packet end=${v.end}") }
                            .doOnComplete { log.w("merkle hubs completed") }
                            .map { v -> v as ScatterSerializable<*> }

                        val ds = datastream.map { v -> v.payload }

                        val out = ScatterSerializable.parseWrapperFromCRC(
                            BlockHeaderPacketParser.parser,
                            ds,
                            parseScheduler
                        ).doOnSuccess { v -> log.v("parsed blockheader end=${v.isEndOfStream}") }
                            .map { header ->
                                WifiDirectRadioModule.BlockDataStream(
                                    header,
                                    ScatterSerializable.parseWrapperFromCRC(
                                        BlockSequencePacketParser.parser,
                                        ds,
                                        parseScheduler
                                    )
                                        .repeat().takeWhile { p -> !p.isEnd },
                                    datastore.cacheDir
                                )
                            }.flatMapCompletable { bds -> datastore.insertMessage(bds) }
                            .doFinally { log.v("out completed") }


                         resp
                             .mergeWith(obs.mergeWith(out))
                             .doOnComplete {
                                 log.v("sending handshake success")
                                 handshake.get().onComplete()
                         }.doOnError { err ->
                             handshake.get().onError(err)
                         }.doFinally {
                             currentMerkleStream.set(MeshtasticPacketStream(MeshtasticMerklePacketParser.parser))
                             currentDataStream.set(MeshtasticPacketStream(MeshtasticStreamPacketParser.parser))
                         }
                             .doOnNext { v -> log.v("sending stream packet ${v.type}") }
                             .doFinally { log.v("all stream packets complete") }
                    }
                }.doFinally { log.v("getDefaultMerkleRoot complete") }
            }
        }.doFinally {
            log.v("handleAnnounceSynAckPacket complete")
        }
    }




    override fun handlePacket(packet: DataPacket): Flowable<ScatterSerializable<*>> {
        return Single.just(packet).flatMapPublisher { v ->
            val p = v.bytes?.fromMeshtastic()
            log.v("meshtastic packet ${p?.type}")
            when (p?.type) {
                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE -> handleAnnouncePacket(p.get(), packet.to)
                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE_ACK -> handleAnnounceAckPacket(p.get())
                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE_SYNACK -> handleAnnounceSynAckPacket(p.get())
                Scatterbrain.MessageType.MESHTASTIC_MERKLE -> currentMerkleStream.get()
                    ?.onPacket(p.get())!!.toFlowable()
                Scatterbrain.MessageType.MESHTASTIC_STREAM -> currentDataStream.get()
                    ?.onPacket(p.get())!!.toFlowable()
                else -> Flowable.just(MeshtasticErrPacket(Scatterbrain.MeshtasticErrCode.INVALID_ARGUMENT))
            }
        }.onErrorResumeNext { e: Throwable ->
            log.e("meshtastic error $e")
            e.printStackTrace()
            when (e) {
                is ErrorStage -> {
                    stage.set(e.stage)
                    Flowable.empty()
                }

                else -> {
                    stage.set(Stage.FAIL)
                    Flowable.error(e)
                }
            }
        }
    }

    override fun awaitHandshake(): Completable {
        return handshake.updateAndGet { v ->
            if (v.hasComplete()) {
                CompletableSubject.create()
            } else {
                v
            }
        }!!
    }
}