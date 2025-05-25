package net.ballmerlabs.uscatterbrain.network.meshtastic

import androidx.lifecycle.AtomicReference
import com.geeksville.mesh.DataPacket
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticAnnounceAckPacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticAnnouncePacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticAnnounceSynAckPacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticErrPacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.fromMeshtastic
import proto.Scatterbrain
import proto.Scatterbrain.MeshtasticAckCode
import proto.Scatterbrain.MeshtasticAnnounceSynAck
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named

@MeshtasticSessionScope
class MeshtasticSessionStateImpl @Inject constructor(
    val connection: MeshtasticConnection,
    val broadcastReceiver: MeshtasticBroadcastReceiverState,
    val database: Datastore,
    val radioModule: MeshtasticRadioModule,
    val advertiser: Advertiser,
    @Named(MeshtasticSessionSubcomponent.ROUTER_ID) val routerId: String,
    ): MeshtasticSessionState {
    val stage = AtomicReference(Stage.ANNOUNCE)
    var remoteLuid: UUID? = null

    override fun <R> mapStageSingle(onSuccess: Stage, func: (Stage) -> Single<R>): Single<R> {
        return Single.fromCallable {
            stage.getAndSet(Stage.LOCKED)
        }.flatMap { s ->
            func(s)
                .doOnSuccess { stage.set(onSuccess) }
        }

    }

    override fun <R> mapStageMaybe(onSuccess: Stage, func: (Stage) -> Maybe<R>): Maybe<R> {
        return Single.fromCallable {
            stage.getAndSet(Stage.LOCKED)
        }.flatMapMaybe { s ->
            func(s)
                .doOnSuccess { stage.set(onSuccess) }

        }
    }

    override fun <R> mapStageObservable(onSuccess: Stage, func: (Stage) -> Observable<R>): Observable<R> {
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

    private fun handleAnnouncePacket(packet: MeshtasticAnnouncePacket): Maybe<ScatterSerializable<*>> {
        if (remoteLuid == null)
            remoteLuid = packet.remoteLuid
        return mapStageMaybe(Stage.ACK) { s ->
            database.merkleDao().getDefaultRoot().flatMapMaybe { root ->
                val code = if (radioModule.getSessionCount() > MAX_SESSIONS) {
                    radioModule.startBacklog(routerId)
                    MeshtasticAckCode.FULL
                }
                else {
                    MeshtasticAckCode.TRANSACTION
                }
                Maybe.just(MeshtasticAnnounceAckPacket(advertiser.getHashLuid(), root.hash!!, code))
            }
        }
    }

    private fun handleAnnounceAckPacket(packet: MeshtasticAnnounceAckPacket): Maybe<ScatterSerializable<*>> {
        return mapStageMaybe(Stage.SYNACK) { s ->
            when (packet.code) {
                MeshtasticAckCode.FULL -> {
                    radioModule.startBacklog(routerId)
                    Maybe.empty()
                }
                else -> {
                  val code = if (radioModule.getSessionCount() > MAX_SESSIONS) {
                      radioModule.startBacklog(routerId)
                      MeshtasticAckCode.FULL
                  }
                  else {
                      MeshtasticAckCode.TRANSACTION
                  }

                  Maybe.just(MeshtasticAnnounceSynAckPacket(code))
              }
            }
        }
    }

    private fun handleAnnounceSynAckPacket(packet: MeshtasticAnnounceSynAckPacket): Maybe<ScatterSerializable<*>> {
        return mapStageMaybe(Stage.MERKLE) { s ->
            when (packet.code) {
                MeshtasticAckCode.FULL -> Maybe.empty()
                MeshtasticAckCode.WAIT -> Maybe.empty()
                //TODO: merkle sync
                else -> Maybe.error(IllegalStateException("not finished"))
            }
        }
    }


    override fun handlePacket(packet: DataPacket): Maybe<ScatterSerializable<*>> {
        return Single.just(packet).flatMapMaybe { v ->
            val p = v.bytes?.fromMeshtastic()
            when (p?.type) {
                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE -> handleAnnouncePacket(p.get())
                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE_ACK -> handleAnnounceAckPacket(p.get())
                Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE_SYNACK -> handleAnnounceSynAckPacket(p.get())
                else -> Maybe.just(MeshtasticErrPacket(Scatterbrain.MeshtasticErrCode.INVALID_ARGUMENT))
            }
        }.onErrorResumeNext { e: Throwable ->
            when(e) {
                is ErrorStage -> {
                    stage.set(e.stage)
                    Maybe.empty()
                }
                else -> {
                    stage.set(Stage.FAIL)
                    Maybe.error(e)
                }
            }
        }
    }
}