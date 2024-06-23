package net.ballmerlabs.uscatterbrain.util

import com.goterl.lazysodium.interfaces.GenericHash
import java.nio.ByteBuffer
import java.util.UUID

fun UUID.toBytes(): ByteArray {
    val bytes = ByteArray(16)
    val buffer = ByteBuffer.wrap(bytes)
    buffer.putLong(mostSignificantBits)
    buffer.putLong(leastSignificantBits)
    return buffer.array()
}

fun ByteArray.toUuid(): UUID {
    val buffer = ByteBuffer.wrap(this)
    val firstLong = buffer.long
    val secondLong = buffer.long
    return UUID(firstLong, secondLong)
}

fun hashAsUUID(hash: ByteArray): UUID {
    return when(hash.size) {
        GenericHash.BYTES, GenericHash.BYTES_MIN,  GenericHash.BYTES_MAX -> {
            val buf = ByteBuffer.wrap(hash)
            //note: this only is safe because crypto_generichash_BYTES_MIN is 16
            UUID(buf.long, buf.long)
        }
        else -> throw IllegalArgumentException("hash size wrong: ${hash.size}")
    }
}