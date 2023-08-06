package net.ballmerlabs.uscatterbrain

import com.polidea.rxandroidble2.RxBleDevice
import java.util.UUID

interface ScatterbrainTransactionFactory {

    fun transaction(device: RxBleDevice, luid: UUID): ScatterbrainTransactionSubcomponent
}