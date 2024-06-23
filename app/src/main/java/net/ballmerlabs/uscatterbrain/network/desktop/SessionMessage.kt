package net.ballmerlabs.uscatterbrain.network.desktop

import net.ballmerlabs.uscatterbrain.network.proto.ApiHeader

interface SessionMessage {
    val header: ApiHeader
}