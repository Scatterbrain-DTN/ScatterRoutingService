package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable

interface ServerResponseTransaction {
    val requestID: Int
    val offset: Int
    val value: ByteArray?
    val remoteDevice: RxBleDevice
    fun sendReply(value: ByteArray?, status: Int): Completable
}