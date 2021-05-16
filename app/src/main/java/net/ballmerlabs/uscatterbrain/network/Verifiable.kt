package net.ballmerlabs.uscatterbrain.network

interface Verifiable {
    val toFingerprint: ByteArray?
    val fromFingerprint: ByteArray?
    val application: String
    val extension: String
    val mime: String
    val toDisk: Boolean
    val hashes: Array<ByteArray>
    val userFilename: String
    val signature: ByteArray?
}