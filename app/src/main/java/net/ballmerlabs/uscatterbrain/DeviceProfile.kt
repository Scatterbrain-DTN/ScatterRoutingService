package net.ballmerlabs.uscatterbrain

import java.util.*

/**
 * General device information and settings storage.
 * Used to refer to a device
 */
class DeviceProfile(var services: HardwareServices, var lUID: UUID) {

    enum class HardwareServices {
        WIFIP2P, BLUETOOTHCLASSIC, BLUETOOTHLE, ULTRASOUND
    }

    fun update(services: HardwareServices) {
        this.services = services
    }

}