package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.github.davidmoten.rx2.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

/**
 * Wrapper class for protocol buffer BlockSequence message
 */
public class BlockSequencePacket implements ScatterSerializable {

    private final int mSequenceNumber;
    private final ByteString mData;
    private File mDataOnDisk;
    private final ScatterProto.BlockSequence mBlockSequence;
    private boolean dataNative;
    private UUID luidtag;

    /**
     * Verify the hash of this message against its header
     *
     * @param bd the bd
     * @return boolean whether verification succeeded
     */
    public boolean verifyHash(BlockHeaderPacket bd) {
        byte[] seqnum = ByteBuffer.allocate(4).putInt(this.mSequenceNumber).order(ByteOrder.BIG_ENDIAN).array();

        byte[] data;
        if (this.mBlockSequence.getDataCase() == ScatterProto.BlockSequence.DataCase.DATA_CONTENTS ) {
            data = this.mBlockSequence.getDataContents().toByteArray();
            this.dataNative = false;
        } else {
            this.dataNative = true;
            data = new byte[0];
        }
        byte[] testhash = new byte[GenericHash.BYTES];
        byte[] state = new byte[LibsodiumInterface.getSodium().crypto_generichash_statebytes()];
        LibsodiumInterface.getSodium().crypto_generichash_init(state,null, 0, testhash.length);
        LibsodiumInterface.getSodium().crypto_generichash_update(state, seqnum, seqnum.length);
        LibsodiumInterface.getSodium().crypto_generichash_update(state, data, data.length);
        LibsodiumInterface.getSodium().crypto_generichash_final(state, testhash, testhash.length);
        return LibsodiumInterface.getSodium().sodium_compare(testhash, bd.getHash(this.mSequenceNumber).toByteArray(), testhash.length) == 0;
    }

    /**
     * Calculates the hash of this message
     *
     * @return the hash
     */
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

    public boolean isNative() {
        return dataNative;
    }

    /**
     * Calculates the hash of this message
     *
     * @return hash as ByteString
     */
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
            CRCProtobuf.writeToCRC(mBlockSequence, os);
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
    public Completable writeToStream(OutputStream os) {
        return Completable.fromAction(() -> CRCProtobuf.writeToCRC(mBlockSequence, os));
    }

    @Override
    public Flowable<byte[]> writeToStream(int fragsize) {
        return Bytes.from(new ByteArrayInputStream(getBytes()), fragsize);
    }

    @Override
    public PacketType getType() {
        return PacketType.TYPE_BLOCKSEQUENCE;
    }

    @Override
    public void tagLuid(UUID luid) {
        luidtag = luid;
    }

    @Override
    public UUID getLuid() {
        return luidtag;
    }

    private BlockSequencePacket(InputStream is) throws IOException {
        this.mBlockSequence = CRCProtobuf.parseFromCRC(ScatterProto.BlockSequence.parser(), is);
        if (mBlockSequence.getDataCase() == ScatterProto.BlockSequence.DataCase.DATA_CONTENTS) {
            this.mData = mBlockSequence.getDataContents();
            this.dataNative = false;
        } else {
            this.mData = ByteString.EMPTY;
            this.dataNative = true;
        }
        this.mSequenceNumber = mBlockSequence.getSeqnum();
    }

    /**
     * Parse from block sequence packet.
     *
     * @param is the is
     * @return the block sequence packet
     */
    public static Single<BlockSequencePacket> parseFrom(InputStream is) {
        return Single.fromCallable(() -> new BlockSequencePacket(is));
    }

     public static Single<BlockSequencePacket> parseFrom(Observable<byte[]> flowable) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return BlockSequencePacket.parseFrom(observer).doFinally(observer::close);
     }

    public static Single<BlockSequencePacket> parseFrom(Flowable<byte[]> flowable) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return BlockSequencePacket.parseFrom(observer).doFinally(observer::close);
    }

    private BlockSequencePacket(Builder builder) {
        this.mSequenceNumber = builder.getmSequenceNumber();
        ByteString d = builder.getmData();
        this.mDataOnDisk = builder.getmDataOnDisk();
        ScatterProto.BlockSequence.Builder tmpbuilder = ScatterProto.BlockSequence.newBuilder();
        if (d != null) {
            tmpbuilder.setDataContents(d);
            this.mData = d;
            this.dataNative = false;
        } else {
            this.dataNative = true;
            this.mData = ByteString.EMPTY;
        }
        this.mBlockSequence = tmpbuilder.setSeqnum(this.mSequenceNumber)
                .build();


    }

    /**
     * Gets sequence number.
     *
     * @return the sequence number
     */
    public int getmSequenceNumber() {
        return mSequenceNumber;
    }

    /**
     * Gets data.
     *
     * @return the data
     */
    public ByteString getmData() {
        return mData;
    }

    /**
     * Gets data on disk.
     *
     * @return the data on disk
     */
    public File getmDataOnDisk() {
        return mDataOnDisk;
    }

    /**
     * Gets block sequence.
     *
     * @return the block sequence
     */
    public ScatterProto.BlockSequence getmBlockSequence() {
        return mBlockSequence;
    }

    /**
     * New builder builder.
     *
     * @return the builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder class for BlockSequencePacket
     */
    public static class Builder {

        private int mSequenceNumber;
        private ByteString mData;
        private File mDataOnDisk;
        private boolean mOnDisk;

        /**
         * Instantiates a new Builder.
         */
        public Builder() {

        }

        /**
         * Sets sequence number.
         *
         * @param sequenceNumber the sequence number
         * @return the sequence number
         */
        public Builder setSequenceNumber(int sequenceNumber) {
            this.mSequenceNumber = sequenceNumber;
            return this;
        }

        /**
         * Sets data.
         *
         * @param data the data
         * @return the data
         */
        public Builder setData(ByteString data) {
            this.mData = data;
            return this;
        }

        /**
         * Build block sequence packet.
         *
         * @return the block sequence packet
         */
        public BlockSequencePacket build() {
            return new BlockSequencePacket(this);
        }

        /**
         * Gets sequence number.
         *
         * @return the sequence number
         */
        public int getmSequenceNumber() {
            return mSequenceNumber;
        }

        /**
         * Gets data.
         *
         * @return the data
         */
        public ByteString getmData() {
            return mData;
        }

        /**
         * Gets file to write packet to
         *
         * @return file object
         */
        public File getmDataOnDisk() {
            return mDataOnDisk;
        }}

}
