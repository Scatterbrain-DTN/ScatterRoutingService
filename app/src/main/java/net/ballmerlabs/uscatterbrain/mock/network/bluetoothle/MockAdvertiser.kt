package net.ballmerlabs.uscatterbrain.mock.network.bluetoothle

import android.app.PendingIntent
import io.ktor.util.Digest
import io.ktor.util.build
import io.reactivex.Completable
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.util.toBytes
import org.mockito.kotlin.mock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class MockAdvertiser : Advertiser {
    val luid = AtomicReference(UUID.randomUUID())
    override fun getRawLuid(): UUID {
        return luid.get()
    }

    override fun getHashLuid(): UUID {
        return luid.get()
    }

    override fun awaitNotBusy(): Completable {
        return Completable.complete()
    }

    override fun setBusy(busy: Boolean) {

    }

    override fun startAdvertise(luid: UUID): Completable {
        return Completable.complete()
    }

    override fun stopAdvertise(): Completable {
        return Completable.complete()
    }

    override fun setAdvertisingLuid(): Completable {
        return Completable.complete()
    }

    override fun setAdvertisingLuid(luid: UUID): Completable {
        return Completable.complete()
    }

    override fun checkForget(luid: UUID): Boolean {
        return true
    }

    override fun forget(luid: UUID) {

    }

    override fun randomizeLuidIfOld(): Boolean {
        return true
    }

    override fun randomizeLuidAndRemove() {

    }

    override fun setRandomizeTimer(minutes: Int) {

    }

    override fun getAlarmIntent(): PendingIntent {
        return mock {  }
    }
}