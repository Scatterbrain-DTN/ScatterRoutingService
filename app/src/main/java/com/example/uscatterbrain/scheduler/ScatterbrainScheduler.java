package com.example.uscatterbrain.scheduler;

import com.example.uscatterbrain.network.ScatterDataPacket;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ScatterbrainScheduler {
    static final String TAG = "Scheduler";
    private static ScatterbrainScheduler mInstance;
    private ScheduledExecutorService mExecutor;
    private  AtomicReference<RoutingServiceState>  mState;
    private  AtomicReference<RoutingServiceState> mPrevState;
    private ScheduledFuture mFuture;


    //tunables for router behavior
    private int mDiscoveryTimeout = 60;
    private int mTransferTimeout = 60;
    private long mUpdateDelay;

    private ScatterbrainScheduler() {
        this.mState =  new AtomicReference<RoutingServiceState>(RoutingServiceState.STATE_SUSPEND);
        this.mExecutor = Executors.newScheduledThreadPool(2);
        this.mUpdateDelay = 10;
    }

    public static ScatterbrainScheduler getInstance() {
        if (mInstance == null) {
            mInstance = new ScatterbrainScheduler();
        }
        return mInstance;
    }
    public RoutingServiceState getRoutingServiceState() {
        return this.mState.get();
    }

    private AtomicReference<RoutingServiceState> scheduleOnce() {
        AtomicReference<RoutingServiceState> newState = mState;


        if (mState.get() == RoutingServiceState.STATE_TRANSFER_PACKETS) {
            // switch from transfer packets if we timeout
            mExecutor.schedule(() -> {
                if (mState.get() == RoutingServiceState.STATE_TRANSFER_PACKETS) {
                    if (mPrevState.get() == RoutingServiceState.STATE_DISCOVER_PEERS) {
                        newState.set(mState.get().resumeDiscover());
                    } else if (mPrevState.get() == RoutingServiceState.STATE_ADVERTISE) {
                        newState.set(mState.get().resumeAdvertise());
                    }
                }
            }, mDiscoveryTimeout, TimeUnit.SECONDS);
        }

        //TODO: race condition?
        if (newState.get() != mState.get()) {
            mPrevState = mState;
        }
        return newState;
    }

    public void start() {
        mFuture = mExecutor.scheduleAtFixedRate(() -> {
            mState = scheduleOnce();
        }, 0, mUpdateDelay, TimeUnit.SECONDS);
    }

    public boolean stop() {
        if (mFuture != null) {
            return mFuture.cancel(false);
        }
        return true;
    }

    public void sendBlockData(List<ScatterDataPacket> packets) {

    }

    public static class InvalidStateChangeException extends Exception {
        private String mState;

        public InvalidStateChangeException(String state) {
            mState = state;
        }

        @Override
        public String toString() {
            return mState;
        }
    }

    private enum RoutingServiceState {
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


}
