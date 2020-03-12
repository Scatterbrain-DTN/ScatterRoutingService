package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class BlockSequencePacket {

    private int mSequenceNumber;
    private ByteString mData;
    private File mDataOnDisk;
    private ScatterProto.BlockSequence mBlockSequence;

    public boolean verifyHash(BlockDataPacket bd) {
        byte[] seqnum = ByteBuffer.allocate(4).putInt(this.mSequenceNumber).order(ByteOrder.BIG_ENDIAN).array();
        byte[] data = this.mBlockSequence.getData().toByteArray();
        byte[] testhash = new byte[GenericHash.BYTES];
        byte[] state = new byte[LibsodiumInterface.getSodium().crypto_generichash_statebytes()];
        LibsodiumInterface.getSodium().crypto_generichash_init(state,null, 0, testhash.length);
        LibsodiumInterface.getSodium().crypto_generichash_update(state, seqnum, seqnum.length);
        LibsodiumInterface.getSodium().crypto_generichash_update(state, data, data.length);
        LibsodiumInterface.getSodium().crypto_generichash_final(state, testhash, testhash.length);
        return LibsodiumInterface.getSodium().sodium_compare(testhash, bd.getHash(this.mSequenceNumber).toByteArray(), testhash.length) == 0;
    }


    public BlockSequencePacket(byte[] data) throws InvalidProtocolBufferException {
        this.mBlockSequence = ScatterProto.BlockSequence.parseFrom(data);
        this.mDataOnDisk = null;
        this.mData = this.mBlockSequence.getData();
        this.mSequenceNumber = this.mBlockSequence.getSeqnum();
    }

    public byte[] getBytes() {
        return mBlockSequence.toByteArray();
    }


    //TODO: implement filestore for database to write files to
    public BlockSequencePacket(InputStream is) throws Exception {
        throw new NotImplementedException();
        /*
        this.mBlockSequence = ScatterProto.BlockSequence.parseDelimitedFrom(is);
        //this.mDataOnDisk = ;
        this.mData = this.mBlockSequence.getData();
        this.mSequenceNumber = this.mBlockSequence.getSeqnum();
        this.mOnDisk = true;
         */
    }

    private BlockSequencePacket(Builder builder) {
        this.mSequenceNumber = builder.getmSequenceNumber();
        this.mData = builder.getmData();
        this.mDataOnDisk = builder.getmDataOnDisk();
        this.mBlockSequence = ScatterProto.BlockSequence.newBuilder()
                .setData(this.mData)
                .setSeqnum(this.mSequenceNumber)
                .build();
    }

    public int getmSequenceNumber() {
        return mSequenceNumber;
    }

    public ByteString getmData() {
        return mData;
    }

    public File getmDataOnDisk() {
        return mDataOnDisk;
    }

    public ScatterProto.BlockSequence getmBlockSequence() {
        return mBlockSequence;
    }

    public static class Builder {

        private int mSequenceNumber;
        private ByteString mData;
        private File mDataOnDisk;
        private boolean mOnDisk;

        public Builder() {

        }

        public Builder setSequenceNumber(int sequenceNumber) {
            this.mSequenceNumber = sequenceNumber;
            return this;
        }

        public Builder setData(ByteString data) {
            this.mData = data;
            return this;
        }
        public BlockSequencePacket build() {
            return new BlockSequencePacket(this);
        }

        public int getmSequenceNumber() {
            return mSequenceNumber;
        }

        public ByteString getmData() {
            return mData;
        }

        public File getmDataOnDisk() {
            return mDataOnDisk;
        }}

    public class NotImplementedException extends Exception {

    }

}
