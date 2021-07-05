package net.ballmerlabs.uscatterbrain.network

import java.util.*

interface Verifiable {
    val toFingerprint: List<UUID>
    val fromFingerprint: List<UUID>
    val application: String
    val extension: String
    val mime: String
    val toDisk: Boolean
    val hashes: Array<ByteArray>
    val userFilename: String
    val sendDate: Long
    val signature: ByteArray?
}