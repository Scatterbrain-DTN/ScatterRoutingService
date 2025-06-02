package net.ballmerlabs.uscatterbrain.network.meshtastic

import androidx.lifecycle.AtomicReference
import com.geeksville.mesh.DataPacket
import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
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
    val stage = AtomicReference(Stage.ANNOUNCE)
    var remoteLuid: UUID? = null
    val currentMerkleStream =
        AtomicReference<MeshtasticPacketStream<MeshtasticMerklePacket, MeshtasticMerkle>?>(null)
    val currentDataStream = AtomicReference<MeshtasticPacketStream<MeshtasticStreamPacket, MeshtasticStream>?>(null)

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
        return mapStagePublisher(Stage.MERKLE) { s ->
            when (packet.code) {
                MeshtasticAckCode.FULL -> Flowable.empty()
                MeshtasticAckCode.WAIT -> Flowable.empty()
                else -> datastore.getDefaultMerkleRoot().flatMapPublisher { root ->
                    val stream = MeshtasticPacketStream(MeshtasticMerklePacketParser.parser)
                    val datastream = MeshtasticPacketStream(MeshtasticStreamPacketParser.parser)
                    currentMerkleStream.getAndSet(stream)?.close()
                    currentDataStream.getAndSet((datastream))?.close()
                    datastore.getMerkleHubs(
                        stream.toFlowable(BackpressureStrategy.BUFFER)
                            .flatMap { h ->
                                Flowable.fromIterable(h.hashes)
                            }
                    ).flatMapPublisher { hubresponse ->

                        val obs: Flowable<ScatterSerializable<*>> =
                            hubresponse.exclude.toList().flatMapObservable { hashes ->
                                datastore.getTopRandomMessages(50, hashes)
                                    .map { v -> v }

                            }.toFlowable(BackpressureStrategy.BUFFER).flatMap { p ->
                                MeshtasticStreamPacket.fromStream(p)
                            }


                        val resp = hubresponse.hubs.toFlowable(BackpressureStrategy.BUFFER)
                            .zipWith(Flowable.interval(0, TimeUnit.SECONDS)) { hub, seq ->
                                MeshtasticMerklePacket(
                                    seq = seq.toInt(),
                                    hashes = listOf(hub.hash!!)
                                ) //TODO batch hashes here
                            }.concatMapLast { v ->
                                MeshtasticMerklePacket(
                                    end = true,
                                    seq = v.seq,
                                    hashes = v.hashes
                                )
                            }
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


                        obs.mergeWith(resp).mergeWith(out)
                    }
                }
            }
        }.toObservable()
    }

    override fun handshake(): Completable {
        return datastore.getDefaultMerkleRoot().flatMapCompletable { root ->
            radioModule.sendPacket(MeshtasticAnnouncePacket(advertiser.getHashLuid(), root).toBroadcast())
        }
    }


    override fun handlePacket(packet: DataPacket): Observable<ScatterSerializable<*>> {
        return Single.just(packet).flatMapObservable { v ->
            val p = v.bytes?.fromMeshtastic()
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