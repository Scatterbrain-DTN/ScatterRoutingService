package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.map
import com.google.protobuf.MessageLite
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import kotlinx.coroutines.flow.Flow
import net.ballmerlabs.scatterproto.ScatterSerializable

enum class Stage {
    LOCKED,
    FAIL,
    WAIT,
    ANNOUNCE,
    ACK,
    SYNACK,
    MERKLE,
    STREAM
}

class ErrorStage(val stage: Stage): Throwable()

data class InvalidStageException(val stage: Stage): Throwable()


interface MeshtasticSessionState {

    fun mapStage(func: (Stage) -> Single<Stage>): Single<Stage>

    fun <R> mapStageSingle(onSuccess: Stage, func: (Stage) -> Single<R>): Single<R>

    fun <R> mapStageObservable(onSuccess: Stage, func: (Stage) -> Observable<R>): Observable<R>

    fun <R> mapStageMaybe(onSuccess: Stage, func: (Stage) -> Maybe<R>): Maybe<R>

    fun <R> mapStagePublisher(onSuccess: Stage, func: (Stage) -> Flowable<R>): Flowable<R>

    fun mapStageCompletable(onSuccess: Stage, func: (Stage) -> Completable): Completable

    fun requireStage(stage: Stage, next: Stage): Single<Stage> {
        return mapStage { s ->
            when(s) {
                stage -> Single.just(next)
                else -> Single.error(InvalidStageException(s))
            }
        }
    }

    fun requireStage(stage: Stage, mapper: () -> Single<Stage>): Single<Stage> {
        return mapStage {  s ->
            when(s) {
                stage -> mapper()
                else -> Single.error(InvalidStageException(s))
            }
        }
    }

    fun handlePacket(packet: DataPacket): Observable<ScatterSerializable<*>>
}