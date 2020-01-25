package com.example.uscatterbrain.eventbus.events;

import com.example.uscatterbrain.eventbus.ScatterEventBusEvent;
import com.example.uscatterbrain.network.BlockDataPacket;

import java.util.List;

public class BlockDataTransactionEvent implements ScatterEventBusEvent {

    public enum TransactionType {
        BD_TRANSACTION_SEND, BD_TRANSACTION_RECEIVE
    }

    private String mComponentID;
    private List<BlockDataPacket> mBlockDataList;
    private TransactionType mTransactionType;

    public BlockDataTransactionEvent(String componentID, TransactionType type) {
        this.mTransactionType = type;
        this.mComponentID = componentID;
    }

    public String getComponentID() {
        return mComponentID;
    }

    public TransactionType getmTransactionType() {
        return mTransactionType;
    }

    public void setContent(List<BlockDataPacket> bdlist) {
        this.mBlockDataList = bdlist;
    }

    public List<BlockDataPacket> getContent() {
        return this.mBlockDataList;
    }
}
