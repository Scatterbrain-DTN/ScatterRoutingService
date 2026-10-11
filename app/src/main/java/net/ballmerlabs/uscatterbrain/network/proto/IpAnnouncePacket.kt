package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.MAX_ADDRESS
import net.ballmerlabs.scatterproto.MAX_IP_LIST
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.scatterproto.toUuid
import scatterbrain.Bootstrap
import scatterbrain.Bootstrap.IpAnnounceItem
import scatterbrain.Scatterbrain.MessageType
import java.net.InetSocketAddress
import java.util.UUID

data class Address (
    val is_uke: Boolean,
    val address: InetSocketAddress
        )

@SbPacket(messageType = MessageType.IP_ANNOUNCE)
class IpAnnouncePacket(
    announce: Bootstrap.IpAnnounce
) : ScatterSerializable<Bootstrap.IpAnnounce>(announce, MessageType.IP_ANNOUNCE) {
    val self: UUID
        get() = packet.self.toUuid()

    val addresses: HashMap<UUID, Address> = announce.itemsList.fold(HashMap()) { map, item ->
        map[item.id.toUuid()] = Address(
            address = InetSocketAddress(item.address, item.port),
            is_uke = item.uke
        )
        map
    }

    override fun validate(): Boolean {
        return addresses.size <= MAX_IP_LIST && packet.itemsList.all { v -> v.address.length <= MAX_ADDRESS }
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
                    .setId(entry.key.toProto())
                    .build()
                list.add(item)
                list
            }

            val builder = Bootstrap.IpAnnounce.newBuilder()
                .setSelf(self.toProto())
                .addAllItems(inner)
                //.setType(Scatterbrain.MessageType.IP_ANNOUNCE)
                .build()

            return IpAnnouncePacket(builder)
        }
    }

    companion object {
        @JvmStatic
        fun newBuilder(self: UUID): Builder {
            return Builder(self)
        }
    }
}