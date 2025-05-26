package net.ballmerlabs.uscatterbrain.network.meshtastic.utils

import androidx.room.concurrent.AtomicInt
import com.google.protobuf.MessageLite
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.subjects.PublishSubject
import net.ballmerlabs.scatterproto.ScatterSerializable
import java.util.TreeMap

class MeshtasticPacketStream<T: SeqLike<U>, U: MessageLite>(
    val parser: ScatterSerializable.Companion.Parser<U, T>
) : Observable<T>() {
    private val currentSeq = AtomicInt(0)
    val buf = TreeMap<Int, T>()
    val obs = PublishSubject.create<T>()

    fun close() {
        obs.onComplete()
    }
    
    @Synchronized
    fun onPacket(packet: T) {
      //  println("onPacket ${packet.seq} ${currentSeq.get()}")
        if (packet.seq == currentSeq.get()) {
            currentSeq.incrementAndGet()
            obs.onNext(packet)
        } else {
            buf[packet.seq] = packet
        }

        var seq = currentSeq.get()
        while (buf.containsKey(seq)) {
            val item: T? = buf.remove(seq++)
            if (item != null) {
                obs.onNext(item)
            }
        }
        currentSeq.set(seq)
    }

    override fun subscribeActual(observer: Observer<in T>?) {
        if (observer != null)
            obs.subscribe(observer)
    }
}