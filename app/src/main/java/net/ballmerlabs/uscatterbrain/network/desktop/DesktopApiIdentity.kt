package net.ballmerlabs.uscatterbrain.network.desktop

import android.os.Parcel
import android.os.ParcelUuid
import com.google.protobuf.ByteString
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterbrainsdk.internal.writeParcelableMap
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.scatterproto.toUuid
import proto.Scatterbrain.ApiIdentity
import proto.Scatterbrain.MessageType
import java.util.UUID

//@SbPacket(messageType = MessageType.API_IDENTITY)
class DesktopApiIdentity(
    packet: ApiIdentity
): ScatterSerializable<ApiIdentity>(packet, MessageType.API_IDENTITY) {

    val fingerprint: UUID = packet.fingerprint.toUuid()

    val isOwned: Boolean = packet.isOwned

    val name: String = packet.name

    val sig: ByteArray = packet.sig.toByteArray()

    val extraKeys: ImmutableMap<String, ByteArray>
        get() = packet.extraMap.map { (k, v) -> Pair(k, v.toByteArray()) }.toMap().toImmutableMap()

    val publicKey: ByteArray = packet.publicKey.toByteArray()


    constructor(
        fingerprint: UUID,
        isOwned: Boolean,
        name: String,
        sig: ByteArray,
        extraKeys: Map<String, ByteArray>,
        publicKey: ByteArray
    ): this(
        ApiIdentity.newBuilder()
            .setFingerprint(fingerprint.toProto())
            .setIsOwned(isOwned)
            .setName(name)
            .setSig(ByteString.copyFrom(sig))
            .putAllExtra(extraKeys.map { (k, v) -> Pair(k, ByteString.copyFrom(v)) }.toMap())
            .setPublicKey(ByteString.copyFrom(publicKey))
            .build()
    )
    override fun validate(): Boolean {
        return true
    }
}