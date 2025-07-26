package net.ballmerlabs.uscatterbrain.network.proto

import android.util.Log
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.goterl.lazysodium.interfaces.SecretBox
import net.ballmerlabs.sbproto.SbPacket
import scatterbrain.Scatterbrain

import java.io.ByteArrayInputStream
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import scatterbrain.Desktop
import scatterbrain.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.CRYPTO_MESSAGE)
class CryptoMessage(
    packet: Desktop.CryptoMessage,
) : ScatterSerializable<Desktop.CryptoMessage>(packet, MessageType.CRYPTO_MESSAGE) {
    override fun validate(): Boolean {
        return packet.nonce.size() == SecretBox.NONCEBYTES && packet.encrypted.size() <= net.ballmerlabs.scatterproto.BLOCK_SIZE_CAP
    }

    fun decryptTypePrefix(privkey: ByteArray): ScatterSerializable.Companion.TypedPacket {
        val bytes = packet.encrypted.toByteArray()
        val nonce = packet.nonce.toByteArray()
        if (bytes.size < SecretBox.MACBYTES) {
            throw IllegalStateException("secretbox invalid size")
        }
        val out = ByteArray(bytes.size - SecretBox.MACBYTES)
        if (LibsodiumInterface.sodium.crypto_secretbox_open_easy(
                out, bytes, bytes.size.toLong(), nonce, privkey
            ) != 0
        ) {
            throw SecurityException("received invalid secretbox")
        }
        val inputStream = ByteArrayInputStream(out)
        return parseTypePrefix(inputStream)
    }

    fun <T : MessageLite, U : ScatterSerializable<T>> decrypt(
        parser: ScatterSerializable.Companion.Parser<T, U>, privkey: ByteArray
    ): T {
        val bytes = packet.encrypted.toByteArray()
        val nonce = packet.nonce.toByteArray()
        if (bytes.size < SecretBox.MACBYTES) {
            throw IllegalStateException("secretbox invalid size")
        }
        val out = ByteArray(bytes.size - SecretBox.MACBYTES)
        if (LibsodiumInterface.sodium.crypto_secretbox_open_easy(
                out, bytes, bytes.size.toLong(), nonce, privkey
            ) != 0
        ) {
            throw SecurityException("received invalid secretbox")
        }

        return parseFromCRC(parser, ByteArrayInputStream(out))
    }


    companion object {

        @OptIn(ExperimentalStdlibApi::class)
        fun <T : MessageLite, U : ScatterSerializable<T>> fromMessage(
            secretKey: ByteArray, message: U
        ): CryptoMessage {
            val bytes = message.bytes

            val out = ByteArray(SecretBox.MACBYTES + bytes.size)
            val nonce = ByteArray(SecretBox.NONCEBYTES)
            LibsodiumInterface.sodium.randombytes_buf(nonce, nonce.size)
            if (LibsodiumInterface.sodium.crypto_secretbox_easy(
                    out, bytes, bytes.size.toLong(), nonce, secretKey
                ) != 0
            ) {
                throw IllegalStateException("failed to encrypt")
            }
            val packet =
                Desktop.CryptoMessage.newBuilder().setNonce(ByteString.copyFrom(nonce))
                    .setEncrypted(ByteString.copyFrom(out)).build()
            val m =  CryptoMessage(packet)
            /*
            val st = m.bytes.toHexString(HexFormat {
                upperCase = false
                bytes {
                    bytesPerGroup = 1
                    groupSeparator = ","
                    bytePrefix = "0x"

                }
                number {
                    removeLeadingZeros = true
                }
            }) + "//"
            for (st in st.chunked(900)) {
                Log.v("message", st)
            }

             */
            return m
        }
    }
}