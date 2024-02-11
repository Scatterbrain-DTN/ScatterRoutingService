package net.ballmerlabs.uscatterbrain.util

import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.mockrxandroidble.RxBleConnectionMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleDeviceMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleScanRecordMock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

fun getBogusRxBleDevice(mac: String): RxBleDevice {
    return RxBleDeviceMock.Builder()
        .deviceMacAddress(mac)
        .deviceName("")
        .bluetoothDevice(mock {
            on { address } doReturn mac
        })
        .connection(
            RxBleConnectionMock.Builder()
                .rssi(1)
                .build()
        )
        .scanRecord(RxBleScanRecordMock.Builder().build())
        .build()
}