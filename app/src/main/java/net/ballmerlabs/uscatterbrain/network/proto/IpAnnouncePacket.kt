package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain
import proto.Scatterbrain.IpAnnounceItem
import java.net.InetSocketAddress
import java.util.UUID
import net.ballmerlabs.scatterproto.*
import proto.Scatterbrain.MessageType

data class Address (
    val is_uke: Boolean,
    val address: InetSocketAddress
        )

@SbPacket(messageType = MessageType.IP_ANNOUNCE)
class IpAnnouncePacket(
    announce: Scatterbrain.IpAnnounce
) : ScatterSerializable<Scatterbrain.IpAnnounce>(announce, MessageType.IP_ANNOUNCE) {
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

            val builder = Scatterbrain.IpAnnounce.newBuilder()
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