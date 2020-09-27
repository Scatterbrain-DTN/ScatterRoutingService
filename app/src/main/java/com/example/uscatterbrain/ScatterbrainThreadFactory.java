package com.example.uscatterbrain;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import io.reactivex.internal.schedulers.NonBlockingThread;

public class ScatterbrainThreadFactory extends AtomicLong implements ThreadFactory {
    @Override
    public Thread newThread(Runnable r) {
        String name = "ScatterbrainThread-" + incrementAndGet();
        Thread t = new ScatterbrainNonblockingThread(r, name);
        t.setPriority(Thread.NORM_PRIORITY);
        t.setDaemon(true);
        return t;
    }

    static final class ScatterbrainNonblockingThread extends Thread implements NonBlockingThread {
        ScatterbrainNonblockingThread(Runnable run, String name) {
            super(run, name);
        }
    }
}
