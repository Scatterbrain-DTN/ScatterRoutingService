package com.example.uscatterbrain;

import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BluetoothLEModuleInternal;

public interface RoutingServiceBackend {
    AdvertisePacket getPacket();
    BluetoothLEModuleInternal getRadioModule();
}
