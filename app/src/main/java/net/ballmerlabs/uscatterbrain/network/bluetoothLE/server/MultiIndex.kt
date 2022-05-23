package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

class MultiIndex<T, V, U> : Map<T, U> {
    private val deviceMap: MutableMap<T, U> = HashMap()
    private val reverseRequestIdMap: MutableMap<U, V> = HashMap()
    private val requestIdMap: MutableMap<V, U> = HashMap()

    fun getMulti(requestid: V): U? {
        return requestIdMap[requestid]
    }

    fun putMulti(integer: V, payload: U): U? {
        if (!deviceMap.containsValue(payload)) {
            return null
        }
        requestIdMap[integer] = payload
        reverseRequestIdMap[payload] = integer
        return payload
    }

    override val size: Int
        get() = deviceMap.size

    override fun isEmpty(): Boolean {
        return deviceMap.isEmpty()
    }

    override fun containsKey(key: T): Boolean {
        return deviceMap.containsKey(key)
    }

    override fun containsValue(value: U): Boolean {
        return deviceMap.containsValue(value)
    }

    override operator fun get(key: T): U? {
        return deviceMap[key]
    }

    fun put(device: T, rxBleServerConnection: U): U? {
        return deviceMap.put(device, rxBleServerConnection)
    }

    fun remove(o: T): U? {
        val connection = deviceMap.remove(o)
        if (reverseRequestIdMap.containsKey(connection)) {
            val key = reverseRequestIdMap[connection]
            if (key != null) {
                requestIdMap.remove(key)
            }
        }
        return connection
    }

    fun putAll(map: Map<out T, U>) {
        deviceMap.putAll(map)
    }

    fun clear() {
        deviceMap.clear()
        reverseRequestIdMap.clear()
        requestIdMap.clear()
    }

    override val keys: Set<T>
        get() = deviceMap.keys

    override val values: Collection<U>
        get() = deviceMap.values

    override val entries: Set<Map.Entry<T, U>>
        get() = deviceMap.entries
}