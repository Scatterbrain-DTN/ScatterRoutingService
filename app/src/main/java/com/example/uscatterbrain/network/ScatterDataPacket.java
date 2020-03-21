package com.example.uscatterbrain.network;

import androidx.annotation.NonNull;

import com.google.protobuf.ByteString;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * High level interface to a scatterbrain blockdata stream,
 * including blockheader and blocksequence packets.
 */
public class ScatterDataPacket implements Iterable<ScatterSerializable>, Iterator<ScatterSerializable> {
    private BlockHeaderPacket mHeader;
    private int mBlockSize;
    private long mSize;
    private int mIndex;
    private boolean mToDisk;
    private InputStream mFragmentStream;
    private boolean mBuildOnFly;
    private List<ByteString> mHashList;

    /**
     * default size of blocksequence packet
     */
    public static final int DEFAULT_BLOCK_SIZE = 1024*1024*1024;
    /**
     * max data size for packets stored in datastore without files
     */
    public static final long MAX_SIZE_NONFILE = 512*1024;


    private ScatterDataPacket(Builder builder) {
        this.mHeader = builder.getHeader();
        this.mFragmentStream = builder.getmFragmentStream();
        this.mBlockSize = builder.getBlockSize();
        this.mBuildOnFly = true;
        this.mHashList = new ArrayList<>();
    }

    /**
     * Verifies the hashes of an existing chain of blocksequnce packets
     * NOTE: this stores everything in memory and is a bad idea.
     *
     * @param seqlist the seqlist
     * @return the boolean
     */
    public boolean verifySequence(List<BlockSequencePacket> seqlist) {
        for (BlockSequencePacket s : seqlist) {
            if (!s.verifyHash(this.mHeader))
                return false;
        }
        return true;
    }



    /* implementation of Iterable<BlockSequencePacket> */

    @NonNull
    @Override
    public Iterator<ScatterSerializable> iterator() {
        return this;
    }

    @Override
    public void forEach(@NonNull Consumer<? super ScatterSerializable> action) {
        Objects.requireNonNull(action);
        for (ScatterSerializable packet : this) {
            action.accept(packet);
        }
    }

    @NonNull
    @Override
    public Spliterator<ScatterSerializable> spliterator() {
        return Spliterators.spliterator(iterator(), mSize ,Spliterator.ORDERED
                | Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.SIZED);
    }

    @Override
    public boolean hasNext() {
        return mIndex < mSize;
    }

    @Override
    public ScatterSerializable next() {
        try {
            ScatterSerializable result = null;
            if (mIndex == 0) {
                result = this.mHeader;
            } else {
                // super hacky limited/capped inputstream. Only lets us read up to mBlocksize
                InputStream is = new CappedInputStream(mFragmentStream, mBlockSize);
                ByteString data = ByteString.readFrom(is);
                BlockSequencePacket bs = new BlockSequencePacket.Builder()
                        .setData(data)
                        .setSequenceNumber(mIndex)
                        .build();

                if (mBuildOnFly) {
                    mHashList.add(bs.calculateHashByteString());
                } else {
                    if (!bs.verifyHash(this.mHeader)) {
                        return null;
                    }
                }
                result = bs;
            }

            mIndex++;
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void remove() {
        //does nothing, removing individual fragments is never a good idea
    }

    @Override
    public void forEachRemaining(@NonNull Consumer<? super ScatterSerializable> action) {
        Objects.requireNonNull(action);
        for (ScatterSerializable packet : this) {
            action.accept(packet);
        }
    }

    /**
     * creates a builder for ScatterDataPacket class
     *
     * @return the builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder for ScatterDataPacket class
     */
    public static class Builder {
        private BlockHeaderPacket mHeader;
        private int mBlockSize;
        private long mSize;
        private boolean mToDisk;
        private byte[] to;
        private byte[] from;
        private String application;
        private int mSessionID;
        private File mFile;
        private InputStream mFragmentStream;

        /**
         * Instantiates a new Builder.
         */
        public Builder() {
            this.mBlockSize = DEFAULT_BLOCK_SIZE;
            this.mToDisk = false;
            this.mSessionID = 0;
        }

        /**
         * Sets recipient key fingerprint (optional)
         *
         * @param to the to
         * @return the to address
         */
        public Builder setToAddress(byte[] to) {
            this.to = to;
            return this;
        }

        /**
         * Sets sender key fingerprint (optional)
         *
         * @param from the from
         * @return the from address
         */
        public Builder setFromAddress(byte[] from) {
            this.from = from;
            return this;
        }

        /**
         * Sets session id.
         *
         * @param sessionID the session id used for bootstrapping to a new transport
         * @return the session id
         */
        public Builder setSessionID(int sessionID) {
            this.mSessionID = sessionID;
            return this;
        }

        /**
         * Sets application name
         *
         * @param application the application
         * @return the application
         */
        public Builder setApplication(String application) {
            this.application = application;
            return this;
        }

        /**
         * Sets file to retrieve BlockSequence packets from
         *
         * @param file the file
         * @return the fragment file
         */
        public Builder setFragmentFile(File file) {
            this.mFile = file;
            this.mToDisk = true;
            return this;
        }

        /**
         * Sets stream to retrieve BlockSequence packets from
         *
         * @param stream the stream
         * @return the fragment stream
         */
        public Builder setFragmentStream(InputStream stream) {
            this.mFragmentStream = stream;
            this.mToDisk = false;
            return this;
        }

        /**
         * Sets fragment count manually
         *
         * @param count the count
         * @return the fragment count
         */
        public Builder setFragmentCount(int count) {
            this.mSize = count;
            return this;
        }

        /**
         * Sets block size manually
         *
         * @param bs the bs
         * @return the block size
         */
        public Builder setBlockSize(int bs) {
            this.mBlockSize = bs;
            return this;
        }

        /**
         * Gets header.
         *
         * @return the header
         */
        public BlockHeaderPacket getHeader() {
            return mHeader;
        }

        /**
         * Gets block size.
         *
         * @return the block size
         */
        public int getBlockSize() {
            return mBlockSize;
        }

        private long getCount() {
            return mSize;
        }

        /**
         * Gets fragment stream.
         *
         * @return the fragment stream
         */
        public InputStream getmFragmentStream() {
            return mFragmentStream;
        }

        /**
         * Builds data packet.
         *
         * @return the scatter data packet
         */
        public ScatterDataPacket build() {
            if (this.mToDisk && this.mFile != null)
                this.mSize = mFile.length();

            if(mFragmentStream == null)
                return  null;

            if (mSize <= 0 || mSize > Integer.MAX_VALUE)
                return null;

            // Make sure that we don't exceed that maximum size for diskless messages
            // TODO: for messages with 1 sequence packet this should be checked elsewhere
            if (!mToDisk && mSize > 1 && (mSize * mBlockSize) > MAX_SIZE_NONFILE ) {
                return  null;
            }

            return new ScatterDataPacket(this);

        }
    }
}
