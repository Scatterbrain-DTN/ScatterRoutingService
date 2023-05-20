package net.ballmerlabs.uscatterbrain.network

import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.ScatterProto.IpAnnounceItem
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID

class IpAnnouncePacket(announce: ScatterProto.IpAnnounce) : ScatterSerializable<ScatterProto.IpAnnounce>(announce) {

    override val type: PacketType
        get() = PacketType.TYPE_IP_ANNOUNCE

    val addresses: HashMap<UUID, InetSocketAddress> = announce.itemsList.fold(HashMap()) { map, item ->
        map[protoUUIDtoUUID(item.id)] = InetSocketAddress(item.address, item.port)
        map
    }

    data class Builder(
         private val addresses: HashMap<UUID, InetSocketAddress> = HashMap()
    ) {

        fun addAddress(luid: UUID, addresss: InetSocketAddress) = apply {
            this.addresses[luid] = addresss
        }

        fun build(): IpAnnouncePacket {
            val inner = addresses.entries.fold(ArrayList<IpAnnounceItem>()) { list, entry ->
                val item = IpAnnounceItem.newBuilder()
                    .setAddress(entry.value.address.toString())
                    .setPort(entry.value.port)
                    .setId(protoUUIDfromUUID( entry.key))
                    .build()
                list.add(item)
                list
            }

            val builder = ScatterProto.IpAnnounce.newBuilder()
                .addAllItems(inner)
                .build()

            return IpAnnouncePacket(builder)
        }
    }

    companion object {
        @JvmStatic
        fun newBuilder(): Builder {
            return Builder()
        }

        class Parser: ScatterSerializable.Companion.Parser<ScatterProto.IpAnnounce, IpAnnouncePacket>(ScatterProto.IpAnnounce.parser())

        fun parser(): Parser {
            return Parser()
        }
    }
}