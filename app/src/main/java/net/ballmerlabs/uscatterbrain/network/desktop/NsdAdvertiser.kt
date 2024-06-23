package net.ballmerlabs.uscatterbrain.network.desktop

import io.reactivex.Completable

interface NsdAdvertiser {
    fun startAdvertise(): Completable
    fun stopAdvertise()
}