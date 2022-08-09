package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.goterl.lazysodium.interfaces.GenericHash
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.ElectLeaderPacket
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.math.BigInteger
import java.util.*

/**
 * the voting stage handles the logic for the leader election algorithm which determines
 * in a semi-trustless fashion if a transport layer bootstrap is required and, if so,
 * to which transport to switch to.
 */
class VotingStage : LeDeviceSession.Stage {
    private val LOG by scatterLog()
    val serverPackets = CompletableSubject.create()
    private val hashedPackets = ArrayList<ElectLeaderPacket>()
    private val unhashedPackets = ArrayList<ElectLeaderPacket>()
    private var tiebreaker = UUID.randomUUID()
    fun getSelf(hashed: Boolean, provides: AdvertisePacket.Provides): ElectLeaderPacket {
        val builder: ElectLeaderPacket.Builder = ElectLeaderPacket.newBuilder()
        if (hashed) {
            builder.enableHashing()
        }
        return builder
                .setProvides(provides)
                .setTiebreaker(tiebreaker)
                .build()
    }

    override fun reset() {
        hashedPackets.clear()
        unhashedPackets.clear()
        tiebreaker = UUID.randomUUID()
    }

    /**
     * add a received electleaderpacket
     * @param packet
     */
    fun addPacket(packet: ElectLeaderPacket) {
        if (packet.isHashed) {
            hashedPackets.add(packet)
        } else {
            unhashedPackets.add(packet)
        }
    }

    /**
     * Currently voting is done by committing to a UUID representing the device and then
     * choosing the uuid with the minimum distance to a collectively chosen random value
     *
     * Random numbers are generated by precommitting to hashed nonces and computing the product
     * of the nonces when all nonces are received. Since a salt is used "cheating" at this
     * should be very hard in the timeframe available to scatterbrain connection
     *
     * The reason we do this is to avoid devices cheating by always becoming UKE/SEME when
     * they want to (which could allow easier data collection / spying for some transports)
     * or by executing a downgrade attack by forcing devices into a less secure transport
     */
    private fun selectLeader(): ElectLeaderPacket {
        var `val` = BigInteger.ONE
        for (packet in unhashedPackets) {
            val newval = BigInteger(ElectLeaderPacket.uuidToBytes(packet.tieBreak))
            `val` = `val`.multiply(newval)
        }
        val hash = ByteArray(GenericHash.BYTES)
        LibsodiumInterface.sodium.crypto_generichash(
                hash,
                hash.size,
                `val`.toByteArray(),
                `val`.toByteArray().size.toLong(),
                null,
                0
        )
        var compare = BigInteger(hash)
        var ret: ElectLeaderPacket? = null
        for (packet in unhashedPackets) {
            val uuid = packet.luid
            if (uuid != null) {
                val c = BigInteger(ElectLeaderPacket.uuidToBytes(uuid))
                if (c.abs() < compare.abs()) {
                    ret = packet
                    compare = c
                }
            } else {
                LOG.w("luid tag was null in tiebreak")
            }
        }
        if (ret == null) {
            throw MiracleException()
        }
        return ret
    }

    private fun tieBreak(): AdvertisePacket.Provides {
        return selectLeader().provides
    }

    fun selectSeme(): UUID? {
        return selectLeader().luid
    }

    private fun countVotes(): Single<AdvertisePacket.Provides> {
        return Single.fromCallable {
            val providesBuckets: MutableMap<AdvertisePacket.Provides, Int> = EnumMap(AdvertisePacket.Provides::class.java)
            for (packet in unhashedPackets) {
                providesBuckets[packet.provides] = 0
                providesBuckets[packet.provides] = providesBuckets[packet.provides]!! + 1
            }
            if (HashSet(providesBuckets.values).size != providesBuckets.values.size) {
                return@fromCallable tieBreak()
            }

            providesBuckets.maxByOrNull { v: Map.Entry<AdvertisePacket.Provides, Int> -> v.value }!!.key
        }
    }

    fun determineUpgrade(): Single<AdvertisePacket.Provides> {
        return countVotes()
    }

    /**
     * verify hashes of packets to prove precommittment
     * @return completable
     */
    fun verifyPackets(): Completable {
        return Completable.defer {
            if (hashedPackets.size != unhashedPackets.size) {
                Completable.error(IllegalStateException("size conflict hashed: ${hashedPackets.size} unhashed: ${unhashedPackets.size}"))
            } else Observable.zip(
                Observable.fromIterable(hashedPackets),
                Observable.fromIterable(unhashedPackets)
            ) { obj, packet -> obj.verifyHash(packet) }
                .flatMap { bool ->
                    if (!bool) {
                        Observable.error(java.lang.IllegalStateException("failed to verify hash"))
                    } else {
                        Observable.just(true)
                    }
                }
                .ignoreElements()
        }
    }

    /**
     * this is thrown in exceedingly rare cases if every device in the local mesh
     * has the same luid. This should only be thrown after the heat death of the universe
     */
    class MiracleException : RuntimeException()

}