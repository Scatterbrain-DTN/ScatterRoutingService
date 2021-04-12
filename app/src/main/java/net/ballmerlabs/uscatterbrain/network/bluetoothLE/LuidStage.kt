package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterRoutingService
import net.ballmerlabs.uscatterbrain.network.LuidPacket
import java.util.*

/**
 * holds state for the luid stage of the bluetooth LE transport FSM
 */
class LuidStage(val selfUnhashed: UUID, val remoteHashedPacket: LuidPacket) : LeDeviceSession.Stage {
    var remoteUnhashed: LuidPacket? = null
    val selfHashedPacket = LuidPacket.newBuilder()
            .setLuid(selfUnhashed)
            .enableHashing(ScatterRoutingService.PROTO_VERSION)
            .build()

    val selfUnhashedPacket = LuidPacket.newBuilder()
            .setLuid(selfUnhashed)
            .build()

    val remoteHashed: UUID
    get() = remoteHashedPacket.hashAsUUID!!

    fun setPacket(packet: LuidPacket?) {
        if (packet!!.isHashed) {
            throw IllegalStateException("packet should not be hashed")
        } else {
            remoteUnhashed = packet
        }
    }

    override fun reset() {
        remoteUnhashed = null
        //TODO: this is not complete
    }

    /**
     * verify the hashes on received luid packets. This prevents a type of attack
     * where a remote device generates a nonrandom luid to cheat at the leader election
     */
    fun verifyPackets(): Completable {
        return if (remoteUnhashed == null) {
            Completable.error(InvalidLuidException("remotepacket not set"))
        } else Single.zip(
                Single.just(remoteHashedPacket),
                Single.just(remoteUnhashed), { obj, packet -> obj.verifyHash(packet) })
                .flatMap { bool: Boolean? ->
                    if (!bool!!) {
                        return@flatMap Single.error<Boolean>(InvalidLuidException("failed to verify hash"))
                    } else {
                        return@flatMap Single.just(true)
                    }
                }
                .ignoreElement()
    }

    class InvalidLuidException(private val reason: String) : Exception() {
        override fun toString(): String {
            return "invalid state in luid stage: $reason"
        }

    }

}