package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Flowable

interface NotificationSetupTransaction {
    val device: RxBleDevice
    fun notify(flowable: Flowable<ByteArray>): Completable
    fun indicate(flowable: Flowable<ByteArray>): Completable
}