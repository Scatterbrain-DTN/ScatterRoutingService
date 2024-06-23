package net.ballmerlabs.scatterproto
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.UUID

enum class Provides(val `val`: Int) {
    INVALID(-1), BLE(0), WIFIP2P(1);

}

data class Optional<T>(
    val item: T? = null,
) {
    val isPresent: Boolean
        get() = item != null

    companion object {
        fun <T> of(v: T): Optional<T> {
            return Optional(v)
        }

        fun <T> empty(): Optional<T> {
            return Optional(null)
        }
    }
}

const val PROTOBUF_PRIVKEY_KEY = "scatterbrain"

fun incrementUUID(uuid: UUID, i: Int): UUID {
    val buffer = ByteBuffer.allocate(16)
    buffer.putLong(uuid.mostSignificantBits)
    buffer.putLong(uuid.leastSignificantBits)
    val b = BigInteger(buffer.array()).add(BigInteger.valueOf(i.toLong()))
    val out = ByteBuffer.wrap(b.toByteArray())
    val high = out.long
    val low = out.long
    return UUID(high, low)
}

fun uuid2bytes(uuid: UUID?): ByteArray? {
    uuid ?: return null
    val buffer = ByteBuffer.allocate(16)
    buffer.putLong(uuid.mostSignificantBits)
    buffer.putLong(uuid.leastSignificantBits)
    return buffer.array()
}

fun bytes2uuid(bytes: ByteArray?): UUID? {
    bytes ?: return null
    val buffer = ByteBuffer.wrap(bytes)
    val high = buffer.long
    val low = buffer.long
    return UUID(high, low)
}