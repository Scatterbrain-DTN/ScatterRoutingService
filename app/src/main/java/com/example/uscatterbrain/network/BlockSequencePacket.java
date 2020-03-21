package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class BlockSequencePacket implements ScatterSerializable {

    private int mSequenceNumber;
    private ByteString mData;
    private File mDataOnDisk;
    private ScatterProto.BlockSequence mBlockSequence;

    public boolean verifyHash(BlockHeaderPacket bd) {
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

    public byte[] calculateHash() {
        byte[] hashbytes = new byte[GenericHash.BYTES];
        byte[] state = new byte[LibsodiumInterface.getSodium().crypto_generichash_statebytes()];
        byte[] seqnum = ByteBuffer.allocate(4).putInt(this.mSequenceNumber).order(ByteOrder.BIG_ENDIAN).array();
        LibsodiumInterface.getSodium().crypto_generichash_init(state, null, 0, hashbytes.length);
        LibsodiumInterface.getSodium().crypto_generichash_update(state, seqnum, seqnum.length);
        LibsodiumInterface.getSodium().crypto_generichash_update(state, mData.toByteArray(), mData.size());
        LibsodiumInterface.getSodium().crypto_generichash_final(state, hashbytes, hashbytes.length);
        return hashbytes;
    }

    public ByteString calculateHashByteString() {
        return ByteString.copyFrom(calculateHash());
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return mBlockSequence;
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            this.mBlockSequence.writeDelimitedTo(os);
            return os.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public ByteString getByteString() {
        return ByteString.copyFrom(getBytes());
    }

    @Override
    public boolean writeToStream(OutputStream os) {
        try {
            mBlockSequence.writeTo(os);
        } catch (IOException e) {
            return false;
        }

        return true;
    }

    private BlockSequencePacket(InputStream is) throws IOException {
        this.mBlockSequence = ScatterProto.BlockSequence.parseDelimitedFrom(is);
        this.mData = mBlockSequence.getData();
        this.mSequenceNumber = mBlockSequence.getSeqnum();
    }

    public static BlockSequencePacket parseFrom(InputStream is) {
        try {
            return new BlockSequencePacket(is);
        } catch (IOException e) {
            return null;
        }
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

    public static Builder newBuilder() {
        return new Builder();
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

}
