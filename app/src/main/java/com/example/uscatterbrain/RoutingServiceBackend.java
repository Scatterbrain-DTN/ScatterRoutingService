package com.example.uscatterbrain;

import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;

public interface RoutingServiceBackend {
    class Applications {
        public static final String APPLICATION_FILESHARING = "fileshare";
    }
    AdvertisePacket getPacket();
    BluetoothLEModule getRadioModule();
    WifiDirectRadioModule getWifiDirect();
    ScatterbrainDatastore getDatastore();
}
