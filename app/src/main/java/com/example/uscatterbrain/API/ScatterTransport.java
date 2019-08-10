package com.example.uscatterbrain.API;
import java.util.UUID;

/**
 * Identifier for low level transport
 */

public interface ScatterTransport {
    UUID getUUID();
    String getNameString();
}
