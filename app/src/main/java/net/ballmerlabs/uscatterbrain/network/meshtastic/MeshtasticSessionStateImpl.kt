package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.content.Context
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.util.toHexString
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.scatterproto.InputStreamFlowableSubscriber
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.RouterPreferences
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
import net.ballmerlabs.uscatterbrain.network.proto.BlockHeaderPacketParser
import net.ballmerlabs.uscatterbrain.network.proto.BlockSequencePacketParser
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.util.concatMapLast
import net.ballmerlabs.uscatterbrain.util.enumerateMap
import net.ballmerlabs.uscatterbrain.util.scatterLog
import scatterbrain.Meshtastic
import scatterbrain.Scatterbrain
import scatterbrain.Meshtastic.MeshtasticAckCode
import scatterbrain.Meshtastic.MeshtasticStream
import scatterbrain.Transfer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
    val preferences: RouterPreferences,
    val context: Context,
    val finalizer: MeshtasticSessionFinalizer,
    @Named(MeshtasticSessionSubcomponent.PARSE_SCHEDULER) val parseScheduler: Scheduler,
    @Named(MeshtasticSessionSubcomponent.ROUTER_ID) val routerId: String,
) : MeshtasticSessionState {

    private val log by scatterLog()

    private val handshakeInProgress = AtomicBoolean(false)

    val stage = AtomicReference(Stage.ANNOUNCE)
    private var remoteLuid: UUID? = null
    private val currentMerkleStream =
        AtomicReference(
            MeshtasticPacketStream(MeshtasticMerklePacketParser.parser)
        )
    private val currentDataStream =
        AtomicReference<Pair<MeshtasticPacketStream<MeshtasticStreamPacket, MeshtasticStream>, InputStreamFlowableSubscriber>?>(
            null
        )

    private val handshake = AtomicReference(CompletableSubject.create())


    private fun getStream(): InputStreamFlowableSubscriber {
        return currentDataStream.updateAndGet { u ->
            when (u) {
                null -> {
                    val stream = MeshtasticPacketStream(MeshtasticStreamPacketParser.parser)
                    val buf = InputStreamFlowableSubscriber(
                        1024 * 64,
                        blocksize = MeshtasticStreamPacket.fragsize
                    )
                    stream.map { v -> v.payload }.subscribe(buf)
                    Pair(stream, buf)
                }

                else -> u
            }
        }!!.second

    }

    fun closeStream() {
        val stream = currentDataStream.getAndSet(null)
        stream?.second?.close()
        currentMerkleStream.set(MeshtasticPacketStream(MeshtasticMerklePacketParser.parser))
    }

    init {
        log.w("MeshtasticSessionStateImpl init $routerId")
        getStream()
    }

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
        func: (Stage) -> Flowable<R>,
    ): Flowable<R> {
        return Single.fromCallable {
            stage.getAndSet(Stage.LOCKED)
        }.flatMapPublisher { s ->
            func(s).doOnComplete { stage.set(onSuccess) }
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

    private fun handleAnnouncePacket(
        packet: MeshtasticAnnouncePacket,
        to: String?,
    ): Flowable<ScatterSerializable<*>> {
        if (remoteLuid == null)
            remoteLuid = packet.remoteLuid
        if (handshakeInProgress.getAndSet(true)) {
            log.w("attempting duplicate handshake with $routerId, ignoring")
            return Flowable.empty()
        }
        return mapStagePublisher(Stage.ACK) { s ->
            log.v("handleAnnouncePacket id=$routerId")
            datastore.getDefaultMerkleRoot().flatMapPublisher { root ->
                val code = if (radioModule.getSessionCount() > MAX_SESSIONS) {
                    radioModule.startBacklog(routerId)
                    MeshtasticAckCode.FULL
                } else {
                    MeshtasticAckCode.TRANSACTION
                }

                when (to) {
                    DataPacket.ID_BROADCAST -> Flowable.just(
                        MeshtasticAnnounceAckPacket(
                            advertiser.getHashLuid(),
                            root,
                            code
                        )
                    ).doOnSubscribe { log.v("replying to broadcast me=$routerId") }

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
                        .map { v ->
                            val scatterSerializable = v as ScatterSerializable<*>
                            scatterSerializable
                        }.concatWith(handleMerkleStream())
                }
            }
        }
    }

    private fun handleMerkleStream(): Flowable<ScatterSerializable<*>> {
        return datastore.getDefaultMerkleRoot().flatMapPublisher { root ->
            log.v("handleMerkleStream root=${root.toHexString()}")
            val stream = currentMerkleStream.updateAndGet { s ->
                when (s) {
                    null -> MeshtasticPacketStream(MeshtasticMerklePacketParser.parser)
                    else -> s
                }
            }!!

            val streamBuffer = getStream()

            val out = ScatterSerializable.parseWrapperFromCRC(
                BlockHeaderPacketParser.parser,
                streamBuffer,
                parseScheduler
            ).doOnSuccess { v -> log.v("parsed blockheader end=${v.isEndOfStream}") }
                .flatMap { header ->
                    if (header.isEndOfStream) {
                        Single.just(true)
                    } else {
                        val s = WifiDirectRadioModule.BlockDataStream(
                            header,
                            ScatterSerializable.parseWrapperFromCRC(
                                BlockSequencePacketParser.parser,
                                streamBuffer,
                                parseScheduler
                            )
                                .repeat()
                                .takeWhile { p -> !p.isEnd }
                                .doOnNext { v -> log.v("parsed sequence ${v.isEnd}") },
                            datastore.cacheDir
                        )
                        datastore.insertMessage(s).andThen(s.await())
                            .doOnComplete { log.w("inserted message!") }
                            .toSingleDefault(false)
                    }
                }.repeat()
                .takeUntil { v -> v }
                .ignoreElements()

                .doFinally { log.v("out completed") }


            datastore.getMerkleHubs(
                stream
                    .concatMap { h ->
                        Flowable.fromIterable(h.hashes)
                    },
                limit = 8
            ).flatMapPublisher { hubresponse ->
                log.w("got hubsresponse")
                val obs: Flowable<ScatterSerializable<*>> =
                    hubresponse.exclude.toList().flatMapPublisher { hashes ->
                        log.w("got merkle hash list ${hashes.size}")
                        preferences.getString(context.getString(R.string.pref_meshtastic), "all")
                            .flatMapPublisher { opt ->
                                when (opt) {
                                    "all" -> datastore.getTopRandomMessages(
                                        50,
                                        hashes,
                                        fileSize = MAX_FORWARD_SIZE
                                    )

                                    else -> datastore.getTopRandomMessages(
                                        50,
                                        hashes,
                                        fileSize = MAX_FORWARD_SIZE,
                                        flag = listOf(Transfer.MessageFlag.FORWARD_MESHTASTIC)
                                    )
                                }
                                    .concatMap { p ->
                                        MeshtasticStreamPacket.fromStream(p)
                                    }.enumerateMap { v, seq ->
                                        MeshtasticStreamPacket(seq, v)
                                    }
                                    .concatMapLast { v ->
                                        MeshtasticStreamPacket(
                                            seq = v.seq,
                                            body = v.payload,
                                            end = true
                                        )
                                    }
                                    .doOnNext { v -> log.v("${routerId} sending stream packet with seq=${v.seq}") }
                                    .map { v ->
                                        val scatterSerializable = v as ScatterSerializable<*>
                                        scatterSerializable
                                    }
                                    .doFinally { log.v("obs completed") }
                            }


                    }.doOnNext { v -> log.v("sending stream packet ${v.type}") }


                val resp = hubresponse.hubs
                    .map { hub ->
                        listOf(hub.bundle.hash!!)
                    }.concatWith(Single.just(listOf()))
                    .enumerateMap { hub, seq ->
                        if (hub.isEmpty()) {
                            MeshtasticMerklePacket(
                                seq = seq,
                                hashes = listOf(),
                                end = true
                            )
                        } else {
                            MeshtasticMerklePacket(
                                seq = seq,
                                hashes = hub,
                                end = false
                            )
                        }

                    }
                    .doOnNext { v -> log.v("sending merkle packet hashes=${v.hashes.size} end=${v.end} size=${v.packet.serializedSize}") }
                    .doOnComplete { log.w("merkle hubs completed") }
                    .map { v ->
                        val scatterSerializable = v as ScatterSerializable<*>
                        scatterSerializable
                    }




                resp
                    .concatWith(obs.mergeWith(out))
                    .doOnError { err ->
                        log.v("sending handshake error $err")
                        handshake.get().onError(err)
                    }
                    .doFinally {
                        closeStream()
                        handshake.get().onComplete()
                        handshakeInProgress.set(false)
                        log.v("all stream packets complete")
                    }
            }
        }.doFinally { log.v("getDefaultMerkleRoot complete") }
    }

    private fun handleAnnounceSynAckPacket(packet: MeshtasticAnnounceSynAckPacket): Flowable<ScatterSerializable<*>> {
        return mapStagePublisher(Stage.MERKLE) { s ->
            log.v("handleAnnounceSynAckPacket id=$routerId code=${packet.code}")
            when (packet.code) {
                MeshtasticAckCode.FULL -> Flowable.empty()
                MeshtasticAckCode.WAIT -> Flowable.empty()
                else -> handleMerkleStream()
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
                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE -> handleAnnouncePacket(
                    p.get(),
                    packet.to
                )

                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE_ACK -> handleAnnounceAckPacket(p.get())
                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE_SYNACK -> handleAnnounceSynAckPacket(p.get())
                Scatterbrain.MessageType.MESHTASTIC_MERKLE -> {
                    val merkle = currentMerkleStream.get()
                    merkle?.onPacket(p.get())!!.toFlowable()
                }

                Scatterbrain.MessageType.MESHTASTIC_STREAM -> {
                    val packet: MeshtasticStreamPacket = p.get()
                    log.v("$routerId received stream packet with seq=${packet.seq}")
                    currentDataStream.get()?.first
                        ?.onPacket(packet)!!.toFlowable()
                }

                else -> Flowable.just(MeshtasticErrPacket(Meshtastic.MeshtasticErrCode.INVALID_ARGUMENT))
            }
        }.doOnNext { p -> log.v("replying with ${p.type}") }
            .onErrorResumeNext { e: Throwable ->
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

    @Throws(Throwable::class)
    protected fun finalize() {
        finalizer.onFinalize()
    }
}