package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.google.protobuf.MessageLite
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

interface CachedLeServerConnection  {
    val mtu: AtomicInteger
    val connection: GattServerConnection
    fun <T : MessageLite> serverNotify(
        packet: ScatterSerializable<T>,
        luid: UUID,
        remoteDevice: RxBleDevice
    ): Completable

    fun dispose()

    fun reset()

    fun unlockLuid(luid: UUID)

    fun isDisposed(): Boolean
}