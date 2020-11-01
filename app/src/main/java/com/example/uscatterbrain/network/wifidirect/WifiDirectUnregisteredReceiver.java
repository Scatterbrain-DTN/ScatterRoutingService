package com.example.uscatterbrain.network.wifidirect;

import android.content.BroadcastReceiver;

public interface WifiDirectUnregisteredReceiver {
    BroadcastReceiver asReceiver();
    WifiDirectBroadcastReceiver asPublic();
}
