package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.BluetoothGattService
import com.polidea.rxandroidble2.Timeout
import java.util.TreeSet
import java.util.UUID

class ServerConfig(
        private val serviceList: MutableMap<UUID, BluetoothGattService> = HashMap(),
        private val phySet: MutableSet<BluetoothPhy> = TreeSet(),
        private val operationTimeout: Timeout? = null
) {
    enum class BluetoothPhy {
        PHY_LE_1M, PHY_LE_2M, PHY_LE_CODED
    }

    fun addPhy(phy: BluetoothPhy): ServerConfig {
        phySet.add(phy)
        return this
    }

    fun removePhy(phy: BluetoothPhy): ServerConfig {
        phySet.remove(phy)
        return this
    }

    fun addService(service: BluetoothGattService): ServerConfig {
        serviceList[service.uuid] = service
        return this
    }

    fun removeService(service: BluetoothGattService): ServerConfig {
        serviceList.remove(service.uuid)
        return this
    }

    fun getOperationTimeout(): Timeout? {
        return operationTimeout
    }

    fun getServices(): Map<UUID, BluetoothGattService> {
        return serviceList
    }

    fun getPhySet(): Set<BluetoothPhy> {
        return phySet
    }

}