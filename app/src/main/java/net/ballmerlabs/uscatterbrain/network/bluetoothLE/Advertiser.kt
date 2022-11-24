package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.Completable
import java.util.*
import java.util.concurrent.atomic.AtomicReference

interface Advertiser {

    val myLuid: AtomicReference<UUID>

    /**
     * Stats LE advertise on scatterbrain UUID
     * This should run offloaded on the adapter until stopAdvertise is called
     */
    fun startAdvertise(luid: UUID? = null): Completable

    /**
     * Stops LE advertise
     */
    fun stopAdvertise(): Completable

    /**
     * Changes the luid value set in the scan response data to the current luid
     * @return completable
     */
    fun setAdvertisingLuid(): Completable

    /**
     * Changes the luid value sent in the scan response data
     * @param luid
     * @return completable
     */
    fun setAdvertisingLuid(luid: UUID): Completable


    /**
     * If the current luid has been around for LUID_RANDOMIZE_DELAY, randomize it
     * @return true if luid was randomized
     */
    fun randomizeLuidIfOld(): Boolean

    fun removeLuid(): Completable
}