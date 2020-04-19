package com.example.uscatterbrain.scheduler;

import com.example.uscatterbrain.eventbus.events.BlockDataTransactionEvent;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.ScatterDataPacket;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ScatterbrainScheduler {
    static final String TAG = "Scheduler";
    private static ScatterbrainScheduler mInstance;
    private ScheduledExecutorService mExecutor;
    private long mUpdateDelay;
    private RoutingServiceState mState;
    private ScheduledFuture mFuture;

    private ScatterbrainScheduler() {
        this.mState = RoutingServiceState.STATE_SUSPEND;
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
        return this.mState;
    }

    private RoutingServiceState scheduleOnce() {
        return null;
    }

    public void start() {
        mFuture = mExecutor.scheduleAtFixedRate(() -> {

        }, 0, mUpdateDelay, TimeUnit.SECONDS);
    }

    public boolean stop() {
        if (mFuture != null) {
            return mFuture.cancel(false);
        }
        return true;
    }

    public void sendBlockData(List<ScatterDataPacket> packets) {
        BlockDataTransactionEvent event = new BlockDataTransactionEvent.BlockDataTransactionEventBuilder("")
                .setContents(packets)
                .setTransactionType(BlockDataTransactionEvent.TransactionType.BD_TRANSACTION_SEND)
                .build();

        EventBus.getDefault().post(event);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onReceiveBlockdata(BlockDataTransactionEvent event) {
        if(event.getmTransactionType() == BlockDataTransactionEvent.TransactionType.BD_TRANSACTION_RECEIVE) {
            this.mExecutor.execute(() -> {
                //ScatterMessage message = new ScatterMessage();
                //TODO: convert from blockdata/blocksequence to scattermessage
            });
        }
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
