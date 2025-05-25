package net.ballmerlabs.uscatterbrain.network.meshtastic.utils

import com.geeksville.mesh.DataPacket
import com.google.protobuf.MessageLite
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.meshtastic.PORT_NUMBER
import net.ballmerlabs.uscatterbrain.network.proto.parseTypePrefixNoCrc
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

fun <T: ScatterSerializable<U>, U: MessageLite> T.toMeshtastic(): ByteArray {
    val b = ByteArrayOutputStream()
    val out = GZIPOutputStream(b)
    out.write(this.bytesNoCrc)
    out.finish()
    out.close()
    return b.toByteArray()
}


fun ByteArray.fromMeshtastic(): ScatterSerializable.Companion.TypedPacket {
    val out = GZIPInputStream(this.inputStream())
    val ret = parseTypePrefixNoCrc(out.readBytes())
    out.close()
    return ret
}

fun <T: ScatterSerializable<U>, U: MessageLite> DataPacket.reply(message: T): DataPacket {
    val bytes = message.toMeshtastic()
    return DataPacket(
        to = this.from,
        bytes = bytes,
        dataType = PORT_NUMBER,
    )
}