package net.ballmerlabs.uscatterbrain.network.meshtastic.utils

import androidx.room.concurrent.AtomicInt
import com.google.protobuf.MessageLite
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.subjects.PublishSubject
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.TreeMap
import java.util.concurrent.ConcurrentLinkedQueue

class MeshtasticPacketStream<T: SeqLike<U>, U: MessageLite>(
    val parser: ScatterSerializable.Companion.Parser<U, T>
) : Observable<T>() {
    private val log by scatterLog()
    private val currentSeq = AtomicInt(0)
    private val waiting = ConcurrentLinkedQueue<T>()
    val buf = TreeMap<Int, T>()
    val obs = PublishSubject.create<T>()

    fun close() {
        log.v("stream for ${parser.type} completed")
        obs.onComplete()
    }

    fun pushPacket(packet: T) {
        if (packet.seq == currentSeq.get()) {
            currentSeq.incrementAndGet()
            obs.onNext(packet)
        } else {
            buf[packet.seq] = packet
        }

        var seq = currentSeq.get()
        while (buf.containsKey(seq)) {
            val item = buf.remove(seq++)
            if (item != null) {
                obs.onNext(item)
            }
        }
        currentSeq.set(seq)
        if(packet.end)
            close()
    }

    @Synchronized
    fun onPacket(packet: T): Maybe<ScatterSerializable<*>> {
        if (obs.hasObservers()) {
            pushPacket(packet)
        } else {
            log.v("waiting add ${packet.seq} ${packet.end}")
            waiting.add(packet)
        }

        return Maybe.empty()
    }

    override fun subscribeActual(observer: Observer<in T>?) {
        if (observer != null) {
            obs.subscribe(observer)
            var t = waiting.poll()
            while(t != null) {
                pushPacket(t)
                t = waiting.poll()
            }
        }
    }
}