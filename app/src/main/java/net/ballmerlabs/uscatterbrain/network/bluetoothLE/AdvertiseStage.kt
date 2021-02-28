package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class AdvertiseStage {
    private val packet = AtomicReference<AdvertisePacket>()
    fun addPacket(packet: AdvertisePacket) {
        this.packet.set(packet)
    }

    val packets: AdvertisePacket
        get() = packet.get()

    companion object {
        private val provides: ArrayList<AdvertisePacket.Provides> = object : ArrayList<AdvertisePacket.Provides>() {
            init {
                add(AdvertisePacket.Provides.BLE)
                add(AdvertisePacket.Provides.WIFIP2P)
            }
        }
        val self: AdvertisePacket = AdvertisePacket.Companion.newBuilder()
                .setProvides(provides)
                .build()!!

    }
}