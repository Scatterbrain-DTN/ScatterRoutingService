package com.example.uscatterbrain.eventbus.events;

import com.example.uscatterbrain.eventbus.ScatterEventBusEvent;
import com.example.uscatterbrain.network.BlockHeaderPacket;

import java.util.List;

public class BlockDataTransactionEvent implements ScatterEventBusEvent {

    public enum TransactionType {
        BD_TRANSACTION_SEND, BD_TRANSACTION_RECEIVE
    }

    private String mComponentID;
    private List<BlockHeaderPacket> mBlockDataList;
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

    public void setContent(List<BlockHeaderPacket> bdlist) {
        this.mBlockDataList = bdlist;
    }

    public List<BlockHeaderPacket> getContent() {
        return this.mBlockDataList;
    }

    public static class BlockDataTransactionEventBuilder {
        private String mComponentID;
        private List<BlockHeaderPacket> mBlockDataList;
        private TransactionType mTransactionType;

        public BlockDataTransactionEventBuilder(String componentID) {
            this.mComponentID = componentID;
        }

        public BlockDataTransactionEventBuilder setContents(List<BlockHeaderPacket> blockHeaderPackets) {
            this.mBlockDataList = blockHeaderPackets;
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
