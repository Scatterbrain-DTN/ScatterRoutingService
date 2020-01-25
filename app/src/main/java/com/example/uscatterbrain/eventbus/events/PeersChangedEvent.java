package com.example.uscatterbrain.eventbus.events;

import com.example.uscatterbrain.eventbus.ScatterEventBusEvent;
import com.example.uscatterbrain.network.AdvertisePacket;

import java.util.List;

public class PeersChangedEvent implements ScatterEventBusEvent {


    public enum TransactionType {
        PC_PEER_ADD, PC_PEER_DROP, PC_PEER_UPDATE
    }

    private String mComponentID;
    private List<AdvertisePacket> mAdvertiseList;
    private TransactionType mTransactionType;

    public PeersChangedEvent(String componentID, TransactionType type) {
        this.mTransactionType = type;
        this.mComponentID = componentID;
    }

    public String getComponentID() {
        return mComponentID;
    }

    public TransactionType getmTransactionType() {
        return mTransactionType;
    }

    public void setContent(List<AdvertisePacket> bdlist) {
        this.mAdvertiseList = bdlist;
        
    }

    public List<AdvertisePacket> getContent() {
        return this.mAdvertiseList;
    }
}
