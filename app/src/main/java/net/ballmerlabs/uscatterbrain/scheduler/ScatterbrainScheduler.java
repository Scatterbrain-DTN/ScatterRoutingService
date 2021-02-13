package net.ballmerlabs.uscatterbrain.scheduler;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public interface ScatterbrainScheduler {

    enum RoutingServiceState {
        STATE_DISCOVER_PEERS {
            @Override
            public RoutingServiceState airplaneMode() {
                return STATE_SUSPEND;
            }

            @Override
            public RoutingServiceState disableDiscovery() {
                return STATE_ADVERTISE;
            }

            @Override
            public RoutingServiceState transferPackets() {
                return STATE_TRANSFER_PACKETS;
            }
        },
        STATE_TRANSFER_PACKETS {
            @Override
            public RoutingServiceState resumeAdvertise() {
                return STATE_ADVERTISE;
            }

            @Override
            public RoutingServiceState resumeDiscover() {
                return STATE_DISCOVER_PEERS;
            }
        },
        STATE_ADVERTISE {
            @Override
            public RoutingServiceState airplaneMode() {
                return STATE_SUSPEND;
            }

            @Override
            public RoutingServiceState enableDiscovery() {
                return STATE_DISCOVER_PEERS;
            }

            @Override
            public RoutingServiceState transferPackets() {
                return STATE_TRANSFER_PACKETS;
            }
        },
        STATE_SUSPEND {
            @Override
            public RoutingServiceState resume() {
                return STATE_ADVERTISE;
            }
        };

        public RoutingServiceState airplaneMode() {
            return null;
        }

        public RoutingServiceState transferPackets() {
            return null;
        }

        public RoutingServiceState resume() {
            return null;
        }

        public RoutingServiceState resumeAdvertise() {
            return null;
        }

        public RoutingServiceState resumeDiscover() {
            return null;
        }

        public RoutingServiceState enableDiscovery() {
            return null;
        }

        public RoutingServiceState disableDiscovery() {
            return null;
        }
    }

    class InvalidStateChangeException extends Exception {
        private final String mState;

        public InvalidStateChangeException(String state) {
            mState = state;
        }

        @Override
        public String toString() {
            return mState;
        }
    }


    RoutingServiceState getRoutingServiceState();

    void start();

    boolean stop();

    boolean isDiscovering();

    boolean isPassive();



    enum TransactionStatus {
        STATUS_SUCCESS,
        STATUS_FAIL
    }
}
