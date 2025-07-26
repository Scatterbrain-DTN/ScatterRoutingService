package net.ballmerlabs.uscatterbrain

import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopApiIdentity
import net.ballmerlabs.uscatterbrain.network.proto.ApiHeader
import scatterbrain.Desktop
import scatterbrain.Scatterbrain

class IdentityResponse(
    packet: Desktop.IdentityResponse
): ScatterSerializable<Desktop.IdentityResponse>(packet, Scatterbrain.MessageType.IDENTITY_RESPONSE) {


    constructor(
        header: ApiHeader,
        identities: List<DesktopApiIdentity>,
        respcode: Desktop.RespCode
    ): this (
        Desktop.IdentityResponse.newBuilder()
                    .setCode(respcode)
                    .setHeader(header.packet)
                    .addAllIdentity(identities.map { v -> v.packet })
                    .build()
    )

    override fun validate(): Boolean {
        return true
    }
}