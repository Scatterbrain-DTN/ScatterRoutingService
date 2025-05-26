package net.ballmerlabs.uscatterbrain.network.meshtastic

import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface

const val PORT_NUMBER = 288


const val prefix = "com.geeksville.mesh"


//
// standard EXTRA bundle definitions
//

// a bool true means now connected, false means not
const val EXTRA_CONNECTED = "$prefix.Connected"
const val EXTRA_PROGRESS = "$prefix.Progress"

/// a bool true means we expect this condition to continue until, false means device might come back
const val EXTRA_PERMANENT = "$prefix.Permanent"

const val EXTRA_PAYLOAD = "$prefix.Payload"
const val EXTRA_NODEINFO = "$prefix.NodeInfo"
const val EXTRA_PACKET_ID = "$prefix.PacketId"
const val EXTRA_STATUS = "$prefix.Status"


const val EXTRA_RECEIVED_SCATTERBRAIN = "$prefix.RECEIVED.$PORT_NUMBER"
const val ACTION_NODE_CHANGE = "$prefix.NODE_CHANGE"
const val ACTION_MESH_CONNECTED = "$prefix.MESH_CONNECTED"
const val ACTION_MESSAGE_STATUS = "$prefix.MESSAGE_STATUS"

const val MAX_SESSIONS = 8
const val MESHTASTIC_MAX_LEN = 200
