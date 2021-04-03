package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.util.Log
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterRoutingService
import net.ballmerlabs.uscatterbrain.network.LuidPacket
import java.util.*
import java.util.concurrent.atomic.AtomicReference

/**
 * holds state for the luid stage of the bluetooth LE transport FSM
 */
class LuidStage(private val uuid: AtomicReference<UUID>) {
    private val hashPackets = ArrayList<LuidPacket>()
    private val realPackets = ArrayList<LuidPacket>()
    private val selfhashed = AtomicReference<LuidPacket?>()
    val self = AtomicReference<LuidPacket?>()
    private fun createSelf(hashed: Boolean): Single<LuidPacket?> {
        return Single.fromCallable {
            val builder: LuidPacket.Builder = LuidPacket.newBuilder()
                    .setLuid(uuid.get())
            if (hashed) {
                builder.enableHashing(ScatterRoutingService.PROTO_VERSION)
            }
            builder.build()
        }
                .doOnSuccess { packet: LuidPacket ->
                    Log.v("debug", "created luid packet: " + packet.luid)
                    if (hashed) {
                        selfhashed.set(packet)
                    } else {
                        self.set(packet)
                    }
                }
    }

    val selfHashed: Single<LuidPacket?>
        get() {
            val packet = selfhashed.get()
            return if (packet == null) {
                createSelf(true)
            } else {
                Single.just(packet)
            }
        }

    fun getSelf(): Single<LuidPacket?> {
        val packet = self.get()
        return if (packet == null) {
            createSelf(false)
        } else {
            Single.just(packet)
        }
    }

    val luid: UUID?
        get() {
            val s = self.get() ?: return null
            return s.luid
        }

    fun addPacket(packet: LuidPacket?) {
        if (packet!!.isHashed) {
            hashPackets.add(packet)
        } else {
            realPackets.add(packet)
        }
    }

    /**
     * verify the hashes on received luid packets. This prevents a type of attack
     * where a remote device generates a nonrandom luid to cheat at the leader election
     */
    fun verifyPackets(): Completable {
        return if (hashPackets.size != realPackets.size) {
            Completable.error(InvalidLuidException("size conflict " +
                    hashPackets.size + " " + realPackets.size))
        } else Observable.zip(
                Observable.fromIterable(hashPackets),
                Observable.fromIterable(realPackets), { obj: LuidPacket?, packet: LuidPacket? -> obj!!.verifyHash(packet) })
                .flatMap { bool: Boolean? ->
                    if (!bool!!) {
                        return@flatMap Observable.error<Boolean>(InvalidLuidException("failed to verify hash"))
                    } else {
                        return@flatMap Observable.just(true)
                    }
                }
                .ignoreElements()
    }

    class InvalidLuidException(private val reason: String) : Exception() {
        override fun toString(): String {
            return "invalid state in luid stage: $reason"
        }

    }

}