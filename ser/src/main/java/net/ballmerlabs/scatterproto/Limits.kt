package net.ballmerlabs.scatterproto

import proto.Scatterbrain.MessageType
import java.util.regex.Pattern
import kotlin.math.floor

val FILE_SANITIZE: Pattern = Pattern.compile("^[a-zA-Z0-9_()-]*$")

const val MAX_APPLICATION_NAME = 256
const val MAX_FILENAME = 256
const val MAX_SIG = 512
const val MAX_HASH = 512
const val MAX_DECLAREHASHES = 4096
const val MAX_KEY = 512
const val MAX_ACK_MESSAGE = 256
const val MAX_PROVIDES_LIST = 64
const val BLOCK_SIZE_CAP = 1024 * 8
const val MAX_IP_LIST = 32
const val MAX_ADDRESS = 32
const val MAX_METADATA = 32
const val MAX_METADATA_VALUE = 512
const val MAX_FORCE_UKE_SIZE = 16
const val MESSAGE_SIZE_CAP = 1024*16
val MAX_BLOCKS = floor( MESSAGE_SIZE_CAP.toDouble() / BLOCK_SIZE_CAP)
const val MAX_FINGERPRINTS = 16

fun isValidFilename(name: String): Boolean {
    return name.length <= MAX_FILENAME && FILE_SANITIZE.matcher(name).matches()
}

fun sanitizeFilename(name: String): String {
    return if (name.length <= MAX_FILENAME && FILE_SANITIZE.matcher(name).matches()) {
        name
    } else {
        throw SecurityException("invalid filename")
    }
}

class MessageSizeException: Throwable()
class MessageValidationException(
    val packet: MessageType
): Throwable("failed to validate packet ${packet.name}")

interface Limits {
    fun validate(): Boolean
}