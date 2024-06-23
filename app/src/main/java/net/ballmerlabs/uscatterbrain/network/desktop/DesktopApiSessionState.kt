package net.ballmerlabs.uscatterbrain.network.desktop

import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import io.reactivex.subjects.PublishSubject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.scatterbrainsdk.internal.readBool
import net.ballmerlabs.scatterbrainsdk.internal.writeBool
import net.ballmerlabs.uscatterbrain.network.proto.DesktopEvent
import net.ballmerlabs.uscatterbrain.network.proto.ImportIdentityResponse
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

fun <T> ConcurrentLinkedQueue<T>.getAll(count: Int? = null): List<T> {
    val head = mutableListOf<T>()
    var item = poll()
    var c = count?:-1
    while (item != null && (c >= 0 || count == null)) {
        c--
        head.add(item)
        item = poll()
    }
    return head
}

data class StateEntry(
    val queue: ConcurrentLinkedQueue<DesktopEvent> = ConcurrentLinkedQueue<DesktopEvent>(),
    val events: PublishSubject<Unit> = PublishSubject.create(),
) {
    fun onEvent(desktopEvent: DesktopEvent) {
        queue.offer(desktopEvent)
        events.onNext(Unit)
    }
}

data class DesktopAddrs(
    val addrs: ImmutableList<DesktopAddr>
): Parcelable {
    constructor(parcel: Parcel) : this((parcel.createTypedArrayList(DesktopAddr.CREATOR)!!.toImmutableList()))

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeTypedList(addrs)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<DesktopAddrs> {
        override fun createFromParcel(parcel: Parcel): DesktopAddrs {
            return DesktopAddrs(parcel)
        }

        override fun newArray(size: Int): Array<DesktopAddrs?> {
            return arrayOfNulls(size)
        }
    }

}

data class DesktopAddr(
    val port: Int,
    val addr: ByteArray,
    val ipv6: Boolean
): Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.createByteArray()!!,
        parcel.readBool()
    ) {
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        other as DesktopAddr

        if (port != other.port) return false
        if (!addr.contentEquals(other.addr)) return false
        if (ipv6 != other.ipv6) return false

        return true
    }

    override fun hashCode(): Int {
        var result = port
        result = 31 * result + addr.contentHashCode()
        result = 31 * result + ipv6.hashCode()
        return result
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(port)
        parcel.writeByteArray(addr)
        parcel.writeBool(ipv6)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<DesktopAddr> {
        override fun createFromParcel(parcel: Parcel): DesktopAddr {
            return DesktopAddr(parcel)
        }

        override fun newArray(size: Int): Array<DesktopAddr?> {
            return arrayOfNulls(size)
        }
    }
}

enum class DesktopPower(val code: Int): Parcelable {
    ENABLED(0),
    DISABLED(1);

    constructor(parcel: Parcel) : this(parcel.readInt())

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(parcel: Parcel, p1: Int) {
        parcel.writeInt(code)
    }

    companion object CREATOR : Parcelable.Creator<DesktopPower> {
        override fun createFromParcel(parcel: Parcel): DesktopPower {
            val code = parcel.readInt()
            return when (code) {
                0 -> ENABLED
                1 -> DISABLED
                else -> throw IllegalStateException("invalid code")
            }
        }

        override fun newArray(size: Int): Array<DesktopPower?> {
            return arrayOfNulls(size)
        }
    }
}


@DesktopApiScope
class DesktopApiSessionState @Inject constructor(
    val context: Context
) {
    val importState  = ConcurrentHashMap<UUID,ImportIdentityResponse>()
    val eventState = ConcurrentHashMap<String, StateEntry>()
}