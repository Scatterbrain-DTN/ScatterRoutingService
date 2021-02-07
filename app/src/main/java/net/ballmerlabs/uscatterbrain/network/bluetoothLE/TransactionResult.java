package net.ballmerlabs.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;

import java.util.NoSuchElementException;

public class TransactionResult<T> {
    public static final String STAGE_EXIT = "exit";
    public static final String STAGE_START = "start";
    public static final String STAGE_LUID_HASHED = "luid-hashed";
    public static final String STAGE_LUID = "luid";
    public static final String STAGE_ADVERTISE = "advertise";
    public static final String STAGE_ELECTION_HASHED = "election-hashed";
    public static final String STAGE_ELECTION = "election";
    public static final String STAGE_UPGRADE = "upgrade";
    public static final String STAGE_BLOCKDATA = "blockdata";
    public final String nextStage;
    public final BluetoothDevice device;
    final T result;
    public TransactionResult(String nextStage, BluetoothDevice device, T result) {
        this.nextStage = nextStage;
        this.device = device;
        this.result = result;
    }

    public TransactionResult(String nextStage, BluetoothDevice device) {
        this(nextStage, device, null);
    }

    public T getResult() {
        if (result == null) {
            throw new NoSuchElementException();
        }
        return result;
    }

    public boolean hasResult() {
        return result != null;
    }
}
