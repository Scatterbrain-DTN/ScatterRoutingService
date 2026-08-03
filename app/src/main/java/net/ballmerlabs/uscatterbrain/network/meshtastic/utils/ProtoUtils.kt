package net.ballmerlabs.uscatterbrain.network.meshtastic.utils

import com.google.protobuf.MessageLite
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.meshtastic.PORT_NUMBER
import net.ballmerlabs.uscatterbrain.util.scatterLog
import okio.ByteString
import okio.ByteString.Companion.toByteString
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
//
//fun <T: ScatterSerializable<U>, U: MessageLite> T.toBroadcast(from: String? = null): DataPacket {
//    val bytes = this.toMeshtastic()
//    val log by scatterLog()
//    log.v("toBroadcast from=$from")
//    return DataPacket(
//        to = DataPacket.ID_BROADCAST,
//        bytes = bytes,
//        dataType = PORT_NUMBER,
//        from = from
//    )
//}
//
//fun ByteArray.fromMeshtastic(): ScatterSerializable.Companion.TypedPacket {
//    val out = GZIPInputStream(this.inputStream())
//    val ret = parseTypePrefixNoCrc(out.readBytes())
//    out.close()
//    return ret
//}
//
//fun ByteString.fromMeshtastic(): ScatterSerializable.Companion.TypedPacket {
//    val out = GZIPInputStream(this.toByteArray().inputStream())
//    val ret = parseTypePrefixNoCrc(out.readBytes())
//    out.close()
//    return ret
//}
//
//fun <T: ScatterSerializable<U>, U: MessageLite> DataPacket.reply(message: T, replyTo: Int, from: String?): DataPacket {
//    val bytes = message.toMeshtastic()
//    val log by scatterLog()
//    log.v("reply from=$from to=$to")
//    return DataPacket(
//        to = from,
//        bytes = bytes,
//        dataType = PORT_NUMBER,
//        replyId = replyTo
//    )
//}