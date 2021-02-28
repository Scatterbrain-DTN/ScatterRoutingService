package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.BiFunction
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.ElectLeaderPacket
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LuidStage.InvalidLuidException
import java.math.BigInteger
import java.util.*
import java.util.Map.Entry.comparingByValue
import kotlin.Comparator
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class VotingStage(private val device: BluetoothDevice) {
    private val hashedPackets = ArrayList<ElectLeaderPacket>()
    private val unhashedPackets = ArrayList<ElectLeaderPacket>()
    private val tiebreaker = UUID.randomUUID()
    fun getSelf(hashed: Boolean, provides: AdvertisePacket.Provides): ElectLeaderPacket {
        val builder: ElectLeaderPacket.Builder = ElectLeaderPacket.Companion.newBuilder()
        if (hashed) {
            builder.enableHashing()
        }
        return builder
                .setProvides(provides)
                .setTiebreaker(tiebreaker)
                .build()
    }

    fun addPacket(packet: ElectLeaderPacket?) {
        if (packet!!.isHashed) {
            hashedPackets.add(packet)
        } else {
            unhashedPackets.add(packet)
        }
    }

    private fun selectLeader(): ElectLeaderPacket {
        var `val` = BigInteger.ONE
        for (packet in unhashedPackets) {
            val newval = BigInteger(ElectLeaderPacket.Companion.uuidToBytes(packet.tieBreak))
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
                val c = BigInteger(ElectLeaderPacket.Companion.uuidToBytes(uuid))
                if (c.abs().compareTo(compare.abs()) < 0) {
                    ret = packet
                    compare = c
                }
            } else {
                Log.w("debug", "luid tag was null in tiebreak")
            }
        }
        if (ret == null) {
            throw MiracleException()
        }
        return ret
    }

    private fun tieBreak(): AdvertisePacket.Provides? {
        return selectLeader().provides
    }

    fun selectSeme(): UUID? {
        return selectLeader().luid
    }

    private fun selector(entry: Map.Entry<AdvertisePacket.Provides, Int>): Int {
        return entry.value
    }

    private fun countVotes(): Single<AdvertisePacket.Provides> {
        return Single.fromCallable {
            val providesBuckets: MutableMap<AdvertisePacket.Provides, Int> = EnumMap(net.ballmerlabs.uscatterbrain.network.AdvertisePacket.Provides::class.java)
            for (packet in unhashedPackets) {
                providesBuckets.putIfAbsent(packet.provides, 0)
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

    fun verifyPackets(): Completable {
        return if (hashedPackets.size != unhashedPackets.size) {
            Completable.error(InvalidLuidException("size conflict"))
        } else Observable.zip(
                Observable.fromIterable(hashedPackets),
                Observable.fromIterable(unhashedPackets), BiFunction { obj: ElectLeaderPacket?, packet: ElectLeaderPacket? -> obj!!.verifyHash(packet) })
                .flatMap { bool: Boolean? ->
                    if (!bool!!) {
                        return@flatMap Observable.error<Boolean>(InvalidLuidException("failed to verify hash"))
                    } else {
                        return@flatMap Observable.just(true)
                    }
                }
                .ignoreElements()
    }

    // this is thrown in exceedingly rare cases if every device in the local mesh
    // has the same luid. This should only be thrown after the heat death of the universe
    class MiracleException : RuntimeException()

}