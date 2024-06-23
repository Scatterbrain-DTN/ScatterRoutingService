@file:net.ballmerlabs.sbproto.SbParser
package net.ballmerlabs.uscatterbrain.network.proto

import io.reactivex.Flowable
import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.scatterproto.InputStreamFlowableSubscriber
import net.ballmerlabs.scatterproto.MESSAGE_SIZE_CAP
import net.ballmerlabs.scatterproto.ScatterSerializable.Companion.TypedPacket


fun parseTypePrefix(
    flowable: Flowable<ByteArray>, scheduler: Scheduler
): Single<TypedPacket> {
    return Single.just(flowable).map { obs ->
        val subscriber =
            InputStreamFlowableSubscriber(MESSAGE_SIZE_CAP)
        obs.subscribe(subscriber)
        parseTypePrefix(subscriber)
    }.subscribeOn(scheduler)
}