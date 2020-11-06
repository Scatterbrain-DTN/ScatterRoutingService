package com.example.uscatterbrain;

import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;

public interface RoutingServiceBackend {
    AdvertisePacket getPacket();
    BluetoothLEModule getRadioModule();
    WifiDirectRadioModule getWifiDirect();
}
