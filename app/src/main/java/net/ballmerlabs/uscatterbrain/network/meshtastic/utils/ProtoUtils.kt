package net.ballmerlabs.uscatterbrain.network.meshtastic.utils

import com.google.protobuf.MessageLite
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.meshtastic.PORT_NUMBER
import net.ballmerlabs.uscatterbrain.network.proto.parseTypePrefixNoCrc
import net.ballmerlabs.uscatterbrain.util.scatterLog
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.send
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

fun <T: ScatterSerializable<U>, U: MessageLite> T.toBroadcast(client: RadioClient, from: String? = null): MeshPacket {
    val bytes = this.toMeshtastic()
    val log by scatterLog()
    log.v("toBroadcast from=$from")
    return MeshPacket(
        to = NodeId.BROADCAST.raw,
        decoded = Data(portnum = PortNum.fromValue(PORT_NUMBER)!!, payload = bytes.toByteString()),
    )
}

fun ByteArray.fromMeshtastic(): ScatterSerializable.Companion.TypedPacket {
    val out = GZIPInputStream(this.inputStream())
    val ret = parseTypePrefixNoCrc(out.readBytes())
    out.close()
    return ret
}

fun ByteString.fromMeshtastic(): ScatterSerializable.Companion.TypedPacket {
    val out = GZIPInputStream(this.toByteArray().inputStream())
    val ret = parseTypePrefixNoCrc(out.readBytes())
    out.close()
    return ret
}

fun <T: ScatterSerializable<U>, U: MessageLite> T.toPacket(message: T, to: Int?): MeshPacket {
    val bytes = message.toMeshtastic()
    val log by scatterLog()
    return MeshPacket(
        to = to?:0,
        decoded = Data(portnum = PortNum.fromValue(PORT_NUMBER)!!, payload = bytes.toByteString()),
    )
}