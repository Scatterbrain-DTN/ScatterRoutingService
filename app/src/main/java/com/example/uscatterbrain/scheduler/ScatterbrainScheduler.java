package com.example.uscatterbrain.scheduler;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uscatterbrain.eventbus.events.BlockDataTransactionEvent;
import com.example.uscatterbrain.network.BlockHeaderPacket;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

public class ScatterbrainScheduler extends Thread {
    static final String TAG = "Scheduler";
    private static ScatterbrainScheduler mInstance;
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

    private RoutingServiceState mState;
    private Handler mHandler;
    private int mTickDelay;

    private ScatterbrainScheduler() {
        this.mState = RoutingServiceState.STATE_SUSPEND;
        this.mTickDelay = 1000;
    }

    public static ScatterbrainScheduler getInstance() {
        if (mInstance == null) {
            mInstance = new ScatterbrainScheduler();
        }
        return mInstance;
    }

    public void setTickDelay(int delay) {
        this.mTickDelay = delay;
    }

    public int getTickDelay() {
        return this.mTickDelay;
    }

    public RoutingServiceState getRoutingServiceState() {
        return this.mState;
    }

    private RoutingServiceState scheduleOnce() {
        return null;
    }

    @Override
    public void interrupt() {
        EventBus.getDefault().unregister(this);
        super.interrupt();
    }

    @Override
    public void run() {
        Looper.prepare();

        EventBus.getDefault().register(this);

        mHandler = new Handler();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                RoutingServiceState tmpState = scheduleOnce();
                if (tmpState == null) {
                    Log.e(TAG, "Attempted an invalid state change. Aborting");
                    return;
                }
                mState = tmpState;
                mHandler.postDelayed(this, mTickDelay);
            }
        });

        Looper.loop();
        EventBus.getDefault().unregister(this);
    }

    public void sendBlockData(List<BlockHeaderPacket> packets) {
        BlockDataTransactionEvent event = new BlockDataTransactionEvent.BlockDataTransactionEventBuilder("")
                .setContents(packets)
                .setTransactionType(BlockDataTransactionEvent.TransactionType.BD_TRANSACTION_SEND)
                .build();

        EventBus.getDefault().post(event);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onReceiveBlockdata(BlockDataTransactionEvent event) {
        if(event.getmTransactionType() == BlockDataTransactionEvent.TransactionType.BD_TRANSACTION_RECEIVE) {
            this.mHandler.post(() -> {
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

}
