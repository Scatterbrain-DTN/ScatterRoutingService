package net.ballmerlabs.uscatterbrain.network

import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.ScatterProto.IpAnnounceItem
import java.net.InetSocketAddress
import java.util.UUID

data class Address (
    val is_uke: Boolean,
    val address: InetSocketAddress
        )

class IpAnnouncePacket(announce: ScatterProto.IpAnnounce) : ScatterSerializable<ScatterProto.IpAnnounce>(announce) {

    override val type: PacketType
        get() = PacketType.TYPE_IP_ANNOUNCE

    val self: UUID
        get() = protoUUIDtoUUID(packet.self)

    val addresses: HashMap<UUID, Address> = announce.itemsList.fold(HashMap()) { map, item ->
        map[protoUUIDtoUUID(item.id)] = Address(
            address = InetSocketAddress(item.address, item.port),
            is_uke = item.uke
        )
        map
    }

    data class Builder(
        val self: UUID,
         private val addresses: HashMap<UUID, InetSocketAddress> = HashMap()
    ) {

        fun addAddress(luid: UUID, addresss: InetSocketAddress) = apply {
            this.addresses[luid] = addresss
        }

        fun build(): IpAnnouncePacket {
            val inner = addresses.entries.fold(ArrayList<IpAnnounceItem>()) { list, entry ->
                val item = IpAnnounceItem.newBuilder()
                    .setAddress(entry.value.address.hostAddress)
                    .setPort(entry.value.port)
                    .setId(protoUUIDfromUUID( entry.key))
                    .build()
                list.add(item)
                list
            }

            val builder = ScatterProto.IpAnnounce.newBuilder()
                .setSelf(protoUUIDfromUUID(self))
                .addAllItems(inner)
                .build()

            return IpAnnouncePacket(builder)
        }
    }

    companion object {
        @JvmStatic
        fun newBuilder(self: UUID): Builder {
            return Builder(self)
        }

        class Parser: ScatterSerializable.Companion.Parser<ScatterProto.IpAnnounce, IpAnnouncePacket>(ScatterProto.IpAnnounce.parser())

        fun parser(): Parser {
            return Parser()
        }
    }
}