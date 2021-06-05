package net.ballmerlabs.uscatterbrain.network

interface Verifiable {
    val toFingerprint: String?
    val fromFingerprint: String?
    val application: String
    val extension: String
    val mime: String
    val toDisk: Boolean
    val hashes: Array<ByteArray>
    val userFilename: String
    val sendDate: Long
    val signature: ByteArray?
}