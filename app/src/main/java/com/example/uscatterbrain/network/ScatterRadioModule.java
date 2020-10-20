package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterRoutingService;

import java.util.List;
import java.util.UUID;

public interface ScatterRadioModule {
    UUID register(ScatterRoutingService service);
    List<UUID> getPeers();
    UUID getModuleID();
    boolean isRegistered();
}
