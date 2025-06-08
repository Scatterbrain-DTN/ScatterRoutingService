package net.ballmerlabs.uscatterbrain.network.meshtastic

import androidx.lifecycle.AtomicReference
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
import io.reactivex.subjects.SingleSubject
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
        AtomicReference<MeshtasticPacketStream<MeshtasticMerklePacket, MeshtasticMerkle>?>(null)
    val currentDataStream = AtomicReference<MeshtasticPacketStream<MeshtasticStreamPacket, MeshtasticStream>?>(null)
    val handshake = AtomicReference(SingleSubject.create<TransactionResult<BootstrapRequest>>())

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

    private fun handleAnnouncePacket(packet: MeshtasticAnnouncePacket): Observable<ScatterSerializable<*>> {
        if (remoteLuid == null)
            remoteLuid = packet.remoteLuid
        return mapStageObservable(Stage.ACK) { s ->
            datastore.getDefaultMerkleRoot().flatMapObservable { root ->
                val code = if (radioModule.getSessionCount() > MAX_SESSIONS) {
                    radioModule.startBacklog(routerId)
                    MeshtasticAckCode.FULL
                } else {
                    MeshtasticAckCode.TRANSACTION
                }
                Observable.just(
                    MeshtasticAnnounceAckPacket(
                        advertiser.getHashLuid(),
                        root,
                        code
                    )
                )
            }
        }
    }

    private fun handleAnnounceAckPacket(packet: MeshtasticAnnounceAckPacket): Observable<ScatterSerializable<*>> {
        return mapStageObservable(Stage.SYNACK) { s ->
            when (packet.code) {
                MeshtasticAckCode.FULL -> {
                    radioModule.startBacklog(routerId)
                    Observable.empty()
                }

                else -> {
                    val code = if (radioModule.getSessionCount() > MAX_SESSIONS) {
                        radioModule.startBacklog(routerId)
                        MeshtasticAckCode.FULL
                    } else {
                        MeshtasticAckCode.TRANSACTION
                    }

                    Observable.just(MeshtasticAnnounceSynAckPacket(code))
                }
            }
        }
    }

    private fun handleAnnounceSynAckPacket(packet: MeshtasticAnnounceSynAckPacket): Observable<ScatterSerializable<*>> {
        return mapStageObservable(Stage.MERKLE) { s ->
            when (packet.code) {
                MeshtasticAckCode.FULL -> Observable.empty()
                MeshtasticAckCode.WAIT -> Observable.empty()
                else -> datastore.getDefaultMerkleRoot().flatMapObservable { root ->
                    val stream = MeshtasticPacketStream(MeshtasticMerklePacketParser.parser)
                    val datastream = MeshtasticPacketStream(MeshtasticStreamPacketParser.parser)
                    currentMerkleStream.getAndSet(stream)?.close()
                    currentDataStream.getAndSet((datastream))?.close()
                    datastore.getMerkleHubs(
                        stream.toFlowable(BackpressureStrategy.BUFFER)
                            .flatMap { h ->
                                Flowable.fromIterable(h.hashes)
                            }
                    ).flatMapObservable { hubresponse ->
                        val obs: Observable<ScatterSerializable<*>>  =
                            hubresponse.exclude.toList().flatMapObservable { hashes ->
                                log.w("got merkle hash list ${hashes.size}")
                                datastore.getTopRandomMessages(50, hashes)
                                    .map { v -> v }

                            }.flatMap { p ->
                                MeshtasticStreamPacket.fromStream(p).toObservable()
                            }


                        val resp = hubresponse.hubs
                            .enumerateMap { hub, seq ->
                                MeshtasticMerklePacket(
                                    seq = seq,
                                    hashes = listOf(hub.hash!!)
                                ) //TODO batch hashes here
                            }.concatMapLast { v ->
                                MeshtasticMerklePacket(
                                    end = true,
                                    seq = v.seq,
                                    hashes = v.hashes
                                )
                            }
                            .doOnNext { v -> log.v("sending merkle packet: ${v.end}") }
                            .doOnComplete { log.w("merkle hubs completed") }
                            .map { v -> v as ScatterSerializable<*> }

                        val ds = datastream.map { v -> v.bytes }

                        val out = ScatterSerializable.parseWrapperFromCRC(
                            BlockHeaderPacketParser.parser,
                            ds,
                            parseScheduler
                        )
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


                         obs.mergeWith(resp).mergeWith(out).doOnComplete {
                             handshake.get()?.onSuccess(TransactionResult.empty())
                         }.doOnError { err ->
                             handshake.get()?.onError(err)
                         }
                    }
                }
            }
        }
    }

    override fun handshake(): Single<TransactionResult<BootstrapRequest>> {
        return handshake.updateAndGet { h ->
            when(h) {
                null -> SingleSubject.create()
                else -> h
            }
        }.toObservable()
            .mergeWith( datastore.getDefaultMerkleRoot().flatMapCompletable { root ->
            log.v("initiate handshake with root ${root.encodeBase64()}")
            radioModule.sendPacket(MeshtasticAnnouncePacket(advertiser.getHashLuid(), root).toBroadcast())
        }).lastOrError()
    }


    override fun handlePacket(packet: DataPacket): Observable<ScatterSerializable<*>> {
        return Single.just(packet).flatMapObservable { v ->
            val p = v.bytes?.fromMeshtastic()
            log.v("meshtastic packet ${p?.type}")
            when (p?.type) {
                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE -> handleAnnouncePacket(p.get())
                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE_ACK -> handleAnnounceAckPacket(p.get())
                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE_SYNACK -> handleAnnounceSynAckPacket(p.get())
                Scatterbrain.MessageType.MESHTASTIC_MERKLE -> currentMerkleStream.get()
                    ?.onPacket(p.get())?.toObservable() ?: Observable.empty()
                Scatterbrain.MessageType.MESHTASTIC_STREAM -> currentDataStream.get()
                    ?.onPacket(p.get())?.toObservable() ?: Observable.empty()
                else -> Observable.just(MeshtasticErrPacket(Scatterbrain.MeshtasticErrCode.INVALID_ARGUMENT))
            }
        }.onErrorResumeNext { e: Throwable ->
            log.e("meshtastic error $e")
            e.printStackTrace()
            when (e) {
                is ErrorStage -> {
                    stage.set(e.stage)
                    Observable.empty()
                }

                else -> {
                    stage.set(Stage.FAIL)
                    Observable.error(e)
                }
            }
        }
    }
}