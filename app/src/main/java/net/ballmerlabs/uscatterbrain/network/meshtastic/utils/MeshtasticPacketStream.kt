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

class MeshtasticPacketStream<T: SeqLike<U>, U: MessageLite>(
    val parser: ScatterSerializable.Companion.Parser<U, T>
) : Observable<T>() {
    private val log by scatterLog()
    private val currentSeq = AtomicInt(0)
    val buf = TreeMap<Int, T>()
    val obs = PublishSubject.create<T>()

    fun close() {
        log.v("stream for ${parser.type} completed")
        obs.onComplete()
    }

    @Synchronized
    fun onPacket(packet: T): Maybe<ScatterSerializable<*>> {
        if (obs.hasObservers()) {
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
        } else {
            if (packet.seq == currentSeq.get()) {
                currentSeq.incrementAndGet()
                buf[packet.seq] = packet
            } else {
                buf[packet.seq] = packet
            }
        }
        if(packet.end)
            close()
        return Maybe.empty()
    }

    override fun subscribeActual(observer: Observer<in T>?) {
        if (observer != null)
            obs.subscribe(observer)
        for ((seq, entry) in buf) {
            if (seq < currentSeq.get())
                obs.onNext(entry)
        }
    }
}