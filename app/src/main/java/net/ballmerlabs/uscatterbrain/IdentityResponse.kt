package net.ballmerlabs.uscatterbrain

import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopApiIdentity
import net.ballmerlabs.uscatterbrain.network.proto.ApiHeader
import proto.Scatterbrain
import proto.Scatterbrain.MessageType

class IdentityResponse(
    packet: Scatterbrain.IdentityResponse
): ScatterSerializable<Scatterbrain.IdentityResponse>(packet, MessageType.IDENTITY_RESPONSE) {

    constructor(
        header: ApiHeader,
        identities: List<DesktopApiIdentity>,
        respcode: Scatterbrain.RespCode
    ): this (
        Scatterbrain.IdentityResponse.newBuilder()
            .addAllIdentity(identities.map { v -> v.packet })
            .setHeader(header.packet)
            .setCode(respcode)
            .build()
    )

    override fun validate(): Boolean {
        return true
    }
}