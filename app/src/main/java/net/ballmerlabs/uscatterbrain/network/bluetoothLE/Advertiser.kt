package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.app.PendingIntent
import io.reactivex.Completable
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket
import java.util.UUID

interface Advertiser {
    fun getRawLuid(): UUID

    fun getHashLuid(): UUID

    fun awaitNotBusy(): Completable

    fun setBusy(busy: Boolean)

    /**
     * Stats LE advertise on scatterbrain UUID
     * This should run offloaded on the adapter until stopAdvertise is called
     */
    fun startAdvertise(luid: UUID): Completable

    /**
     * Stops LE advertise
     */
    fun stopAdvertise(): Completable

    /**
     * Changes the luid value set in the scan response data to the current luid
     * @return completable
     */
    fun setAdvertisingLuid(): Completable

    fun clear(boolean: Boolean)

    /**
     * Changes the luid value sent in the scan response data
     * @param luid
     * @return completable
     */
    fun setAdvertisingLuid(luid: UUID = getHashLuid(), ukes: Map<UUID, net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket> = mapOf()): Completable

    fun checkForget(luid: UUID): Boolean

    fun forget(luid: UUID)

    /**
     * If the current luid has been around for LUID_RANDOMIZE_DELAY, randomize it
     * @return true if luid was randomized
     */
    fun randomizeLuidIfOld(): Boolean

    fun randomizeLuidAndRemove()

    fun setRandomizeTimer(minutes: Int)
    fun getAlarmIntent(): PendingIntent
    companion object {
        val CLEAR_DATA = UUID.fromString("00005BC5-0000-1000-8000-00805F9B34FB")
        val LUID_DATA = UUID.fromString("0000FC87-0000-1000-8000-00805F9B34FB")
        val UKES_DATA = UUID.fromString("0000FC88-0000-1000-8000-00805F9B34FB")
    }
}