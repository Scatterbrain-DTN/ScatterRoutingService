package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.Completable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.db.hashAsUUID
import net.ballmerlabs.uscatterbrain.network.LuidPacket
import java.util.UUID

/**
 * holds state for the luid stage of the bluetooth LE transport FSM
 */
class LuidStage(val selfUnhashed: UUID, val remoteHashed: UUID) : LeDeviceSession.Stage {
    private var remoteUnhashed: LuidPacket? = null
    val selfUnhashedPacket = LuidPacket.newBuilder()
            .setLuid(selfUnhashed)
            .build()

    fun setPacket(packet: LuidPacket) {
        if (packet.isHashed) {
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
                Single.just(remoteHashed),
                Single.just(remoteUnhashed)) { hashed, unhashed ->
            val hash = LuidPacket.calculateHashFromUUID(unhashed.luidVal)
            hashAsUUID(hash).compareTo(hashed) == 0
        }
                .flatMap { bool ->
                    if (!bool) {
                        Single.error(InvalidLuidException("failed to verify hash"))
                    } else {
                        Single.just(true)
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