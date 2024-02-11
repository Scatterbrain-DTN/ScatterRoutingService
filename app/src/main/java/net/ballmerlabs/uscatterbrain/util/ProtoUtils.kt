package net.ballmerlabs.uscatterbrain.util

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