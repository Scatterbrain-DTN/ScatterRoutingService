package com.example.uscatterbrain.API;

import com.example.uscatterbrain.network.BlockDataPacket;

/**
 * Used by highlevel api. run() is called when packets are recieved.
 */

public interface OnRecieveCallback {

    void run(BlockDataPacket[] packets);
}
