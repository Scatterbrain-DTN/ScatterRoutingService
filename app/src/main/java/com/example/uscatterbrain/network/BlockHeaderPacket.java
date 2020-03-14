package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class BlockHeaderPacket {
    private int mNumSequence;
    private int mBlockSize;
    private ScatterProto.BlockHeader blockHeader;

    public BlockHeaderPacket(Builder builder) {
        this.mNumSequence = builder.getNumSequence();
        this.mBlockSize = builder.getBlockSize();
        this.blockHeader = ScatterProto.BlockHeader.newBuilder()
                .setBlocksize(this.mBlockSize)
                .setSequencecount(this.mNumSequence)
                .build();
    }

    public BlockHeaderPacket(byte[] data) throws InvalidProtocolBufferException  {
        this.blockHeader = ScatterProto.BlockHeader.parseFrom(data);
        this.mNumSequence = blockHeader.getSequencecount();
        this.mBlockSize = blockHeader.getBlocksize();
    }

    public BlockHeaderPacket(InputStream is) throws IOException {
        this.blockHeader = ScatterProto.BlockHeader.parseFrom(is);
        this.mBlockSize = blockHeader.getBlocksize();
        this.mNumSequence = blockHeader.getSequencecount();
    }

    public ByteString getBytes() {
        return blockHeader.toByteString();
    }

    public void writeToOutputStream(OutputStream os) throws IOException{
        this.blockHeader.writeTo(os);
    }

    public int getNumSequence() {
        return mNumSequence;
    }

    public int getBlockSize() {
        return mBlockSize;
    }

    public static class Builder {
        private int mNumSequence;
        private int mBlockSize;

        public Builder() {
            mBlockSize = BlockDataPacket.DEFAULT_BLOCK_SIZE;
            mNumSequence = -1;
        }

        public Builder setNumSequence(int numSequence) {
            this.mNumSequence = numSequence;
            return this;
        }

        public Builder setBlockSize(int blockSize) {
            this.mBlockSize = blockSize;
            return this;
        }

        public BlockHeaderPacket build() {
            if (mNumSequence < 0)
                return null;

            return new BlockHeaderPacket(this);
        }

        public int getNumSequence() {
            return mNumSequence;
        }

        public int getBlockSize() {
            return mBlockSize;
        }
    }
}
