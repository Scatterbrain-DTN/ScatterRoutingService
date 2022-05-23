package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import io.reactivex.Completable

interface ServerResponseTransaction {
    val requestID: Int
    val offset: Int
    val value: ByteArray?
    fun sendReply(value: ByteArray?, status: Int): Completable
}