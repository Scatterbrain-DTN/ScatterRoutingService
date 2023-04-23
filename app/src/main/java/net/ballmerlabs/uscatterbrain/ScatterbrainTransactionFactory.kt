package net.ballmerlabs.uscatterbrain

import com.polidea.rxandroidble2.RxBleDevice

interface ScatterbrainTransactionFactory {

    fun transaction(device: RxBleDevice): ScatterbrainTransactionSubcomponent
}