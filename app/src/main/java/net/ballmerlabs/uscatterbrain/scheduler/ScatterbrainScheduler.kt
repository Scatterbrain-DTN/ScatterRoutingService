package net.ballmerlabs.uscatterbrain.scheduler

/**
 * dagger2 interface for ScatterbrainScheduler
 */
interface ScatterbrainScheduler {
    enum class RoutingServiceState {
        STATE_DISCOVER_PEERS {
            override fun airplaneMode(): RoutingServiceState {
                return STATE_SUSPEND
            }

            override fun disableDiscovery(): RoutingServiceState {
                return STATE_ADVERTISE
            }

            override fun transferPackets(): RoutingServiceState {
                return STATE_TRANSFER_PACKETS
            }
        },
        STATE_TRANSFER_PACKETS {
            override fun resumeAdvertise(): RoutingServiceState {
                return STATE_ADVERTISE
            }

            override fun resumeDiscover(): RoutingServiceState {
                return STATE_DISCOVER_PEERS
            }
        },
        STATE_ADVERTISE {
            override fun airplaneMode(): RoutingServiceState {
                return STATE_SUSPEND
            }

            override fun enableDiscovery(): RoutingServiceState {
                return STATE_DISCOVER_PEERS
            }

            override fun transferPackets(): RoutingServiceState {
                return STATE_TRANSFER_PACKETS
            }
        },
        STATE_SUSPEND {
            override fun resume(): RoutingServiceState {
                return STATE_ADVERTISE
            }
        };

        open fun airplaneMode(): RoutingServiceState? {
            return null
        }

        open fun transferPackets(): RoutingServiceState? {
            return null
        }

        open fun resume(): RoutingServiceState? {
            return null
        }

        open fun resumeAdvertise(): RoutingServiceState? {
            return null
        }

        open fun resumeDiscover(): RoutingServiceState? {
            return null
        }

        open fun enableDiscovery(): RoutingServiceState? {
            return null
        }

        open fun disableDiscovery(): RoutingServiceState? {
            return null
        }
    }

    class InvalidStateChangeException(private val mState: String) : Exception() {
        override fun toString(): String {
            return mState
        }

    }

    val routingServiceState: RoutingServiceState
    fun start()
    fun stop(): Boolean
    val isDiscovering: Boolean
    val isPassive: Boolean
}