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

    public BlockDataTransactionEvent(BlockDataTransactionEventBuilder builder) {
        this.mTransactionType =  builder.mTransactionType;
        this.mComponentID = builder.mComponentID;
        this.mBlockDataList = builder.mBlockDataList;
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

    public static class BlockDataTransactionEventBuilder {
        private String mComponentID;
        private List<BlockDataPacket> mBlockDataList;
        private TransactionType mTransactionType;

        public BlockDataTransactionEventBuilder(String componentID) {
            this.mComponentID = componentID;
        }

        public BlockDataTransactionEventBuilder setContents(List<BlockDataPacket> blockDataPackets) {
            this.mBlockDataList = blockDataPackets;
            return this;
        }

        public BlockDataTransactionEventBuilder setTransactionType(TransactionType type) {
            this.mTransactionType = type;
            return this;
        }

        public BlockDataTransactionEvent build() {
            BlockDataTransactionEvent event = new BlockDataTransactionEvent(this);
            return event;
        }
    }
}
