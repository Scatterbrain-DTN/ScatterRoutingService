package com.example.uscatterbrain.scheduler;

import com.example.uscatterbrain.network.BlockDataObservableSource;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

public class ScatterbrainSchedulerImpl implements ScatterbrainScheduler{
    static final String TAG = "Scheduler";
    private ScheduledExecutorService mExecutor;
    private  AtomicReference<RoutingServiceState>  mState;
    private  AtomicReference<RoutingServiceState> mPrevState;
    private ScheduledFuture mFuture;


    //tunables for router behavior
    private int mDiscoveryTimeout = 60;
    private int mTransferTimeout = 60;
    private long mUpdateDelay;

    @Inject
    public ScatterbrainSchedulerImpl() {
        this.mState =  new AtomicReference<RoutingServiceState>(RoutingServiceState.STATE_SUSPEND);
        this.mExecutor = Executors.newScheduledThreadPool(2);
        this.mUpdateDelay = 10;
    }

    @Override
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

    @Override
    public void start() {
        mFuture = mExecutor.scheduleAtFixedRate(() -> {
            mState = scheduleOnce();
        }, 0, mUpdateDelay, TimeUnit.SECONDS);
    }

    @Override
    public boolean stop() {
        if (mFuture != null) {
            return mFuture.cancel(false);
        }
        return true;
    }

    @Override
    public void sendBlockData(List<BlockDataObservableSource> packets) {

    }
}
