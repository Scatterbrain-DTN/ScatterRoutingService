package net.ballmerlabs.uscatterbrain.network

import java.util.*

/**
 * Fields for an ed25519 verifiable Scatterbrain message. Used
 * for providing a common verification interface between database and network
 * entities
 */
interface Verifiable {
    val toFingerprint: List<UUID>
    val fromFingerprint: List<UUID>
    val application: String
    val extension: String
    val mime: String
    val isFile: Boolean
    val hashes: Array<ByteArray>
    val userFilename: String
    val sendDate: Long
    val signature: ByteArray?
}