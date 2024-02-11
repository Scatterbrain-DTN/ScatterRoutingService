package net.ballmerlabs.uscatterbrain

import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.ServiceTestRule
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.mockrxandroidble.RxBleConnectionMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleDeviceMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleScanRecordMock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import net.ballmerlabs.scatterbrainsdk.BinderProvider
import net.ballmerlabs.scatterbrainsdk.BinderWrapper
import net.ballmerlabs.scatterbrainsdk.ScatterbrainBinderApi
import net.ballmerlabs.scatterbrainsdk.internal.BinderWrapperImpl
import net.ballmerlabs.scatterbrainsdk.internal.MockBinderProvider
import net.ballmerlabs.scatterbrainsdk.internal.ScatterbrainBroadcastReceiverImpl
import org.junit.Before
import org.junit.Rule
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

fun getBogusRxBleDevice(mac: String): RxBleDevice {
    return RxBleDeviceMock.Builder()
        .deviceMacAddress("ff:ff:ff:ff:ff:ff")
        .deviceName("")
        .connection(
            RxBleConnectionMock.Builder()
                .rssi(1)
                .build()
        )
        .scanRecord(RxBleScanRecordMock.Builder().build())
        .build()!!
}

abstract class TestBase {
    protected val testCoroutineScope = CoroutineScope(Dispatchers.Default)
    protected lateinit var binder: BinderWrapper
    protected lateinit var regularBinder: ScatterbrainBinderApi

    @get:Rule
    val serviceRule = ServiceTestRule()

    @ExperimentalCoroutinesApi
    @Before
    open fun init() {
        val bindIntet = Intent(
                ApplicationProvider.getApplicationContext(),
                ScatterRoutingService::class.java
        )

        val b: IBinder = serviceRule.bindService(bindIntet)
        val binderProvider: BinderProvider = MockBinderProvider(b)
        val broadcastReceiver= ScatterbrainBroadcastReceiverImpl()
        broadcastReceiver.coroutineScope = testCoroutineScope
        broadcastReceiver.context = ApplicationProvider.getApplicationContext()
        binder = BinderWrapperImpl(
                ApplicationProvider.getApplicationContext(),
                broadcastReceiver,
                binderProvider,
            testCoroutineScope
        )
        runBlocking { binder.startService() }
        regularBinder = ScatterbrainBinderApi.Stub.asInterface(b)
        regularBinder.clearDatastore()
        broadcastReceiver.register()
    }
}