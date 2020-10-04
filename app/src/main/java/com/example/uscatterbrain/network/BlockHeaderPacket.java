package com.example.uscatterbrain.network;

import android.util.Log;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;
import com.goterl.lazycode.lazysodium.interfaces.Sign;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Single;

/**
 * Wrapper class for protocol buffer blockdata message
 */
public class BlockHeaderPacket implements ScatterSerializable {
    private ScatterProto.BlockData blockdata;
    private List<ByteString> mHashList;
    private ByteString mFromFingerprint;
    private ByteString mToFingerprint;
    private byte[] mSignature;
    private byte[] mApplication;
    private int mSessionID;
    private boolean mToDisk;
    private int mBlocksize;

    private BlockHeaderPacket(Builder builder) {
        this.mHashList = builder.getHashlist();
        if (builder.getSig() == null) {
            this.mSignature = new byte[Sign.ED25519_BYTES];
        } else {
            this.mSignature = builder.getSig().toByteArray();
        }
        this.mToFingerprint = builder.getmToFingerprint();
        this.mFromFingerprint = builder.getmFromFingerprint();
        this.mApplication = builder.getApplication();
        this.mSessionID = builder.getSessionid();
        this.mToDisk = builder.getToDisk();
        this.mBlocksize = builder.getBlockSize();
        Log.e("debug", "adding all nexthashes " + mHashList.size());
        this.blockdata = ScatterProto.BlockData.newBuilder()
                .setApplicationBytes(ByteString.copyFrom(this.mApplication))
                .setFromFingerprint(this.mFromFingerprint)
                .setToFingerprint(this.mToFingerprint)
                .setTodisk(this.mToDisk)
                .addAllNexthashes(this.mHashList)
                .setSessionid(this.mSessionID)
                .setSig(ByteString.copyFrom(this.mSignature))
                .setBlocksize(mBlocksize)
                .build();
    }

    private ByteString sumBytes() {
        ByteString messagebytes = ByteString.EMPTY;
        messagebytes = messagebytes.concat(this.mFromFingerprint);
        messagebytes = messagebytes.concat(this.mToFingerprint);
        messagebytes = messagebytes.concat(ByteString.copyFrom(this.mApplication));
        byte td = 0;
        if (this.mToDisk)
            td = 1;
        ByteString toDiskBytes = ByteString.copyFrom(ByteBuffer.allocate(1).order(ByteOrder.BIG_ENDIAN).put(td).array());
        messagebytes = messagebytes.concat(toDiskBytes);

        for (ByteString hash : this.mHashList) {
            messagebytes = messagebytes.concat(hash);
        }
        return messagebytes;
    }

    /**
     * Verifyed 25519 boolean.
     *
     * @param pubkey the pubkey
     * @return the boolean
     */
    public boolean verifyed25519(byte[] pubkey) {
        if (pubkey.length != Sign.PUBLICKEYBYTES)
            return false;

        ByteString messagebytes = sumBytes();
        return LibsodiumInterface.getSodium().crypto_sign_verify_detached(this.blockdata.getSig().toByteArray(),
                messagebytes.toByteArray(),
                messagebytes.size(),
                pubkey) == 0;
    }

    /**
     * Sign ed 25519 boolean.
     *
     * @param secretkey the secretkey
     * @return the boolean
     */
    public boolean signEd25519(byte[] secretkey) {
        if (secretkey.length != Sign.SECRETKEYBYTES)
            return false;

        ByteString messagebytes = sumBytes();

        this.mSignature = new byte[Sign.ED25519_BYTES];
        Pointer p = new PointerByReference(Pointer.NULL).getPointer();
        if (LibsodiumInterface.getSodium().crypto_sign_detached(this.mSignature,
                p, messagebytes.toByteArray(), messagebytes.size(), secretkey) == 0) {
            this.blockdata = ScatterProto.BlockData.newBuilder()
                    .setApplicationBytes(ByteString.copyFrom(this.mApplication))
                    .setFromFingerprint(this.mFromFingerprint)
                    .setToFingerprint(this.mToFingerprint)
                    .setTodisk(this.mToDisk)
                    .addAllNexthashes(this.mHashList)
                    .setSessionid(this.mSessionID)
                    .setBlocksize(mBlocksize)
                    .setSig(ByteString.copyFrom(this.mSignature))
                    .build();
            return true;
        } else {
            return false;
        }
    }

    private BlockHeaderPacket(InputStream in) throws IOException {
        this.blockdata = ScatterProto.BlockData.parseDelimitedFrom(in);
        this.mApplication = blockdata.getApplicationBytes().toByteArray();
        Log.e("debug ", "header nexthashes count" + blockdata.getNexthashesList().size());
        Log.e("debug", "header nexthashes raw count " + blockdata.getNexthashesCount());
        this.mHashList = blockdata.getNexthashesList();
        this.mFromFingerprint = blockdata.getFromFingerprint();
        this.mToFingerprint = blockdata.getToFingerprint();
        this.mSignature = blockdata.getSig().toByteArray();
        this.mToDisk = blockdata.getTodisk();
        this.mSessionID = blockdata.getSessionid();
        this.mBlocksize = blockdata.getBlocksize();
    }

    /**
     * Parse from blockheader packet.
     *
     * @param is the is
     * @return the block header packet
     */
    public static Single<BlockHeaderPacket> parseFrom(InputStream is) {
        return Single.fromCallable(() -> new BlockHeaderPacket(is));
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            this.blockdata.writeDelimitedTo(os);
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
        return Completable.fromAction(() -> blockdata.writeDelimitedTo(os));
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return blockdata;
    }

    /**
     * Gets blockdata.
     *
     * @return the blockdata
     */
    public ScatterProto.BlockData getBlockdata() {
        return this.blockdata;
    }

    /**
     * Gets the blocksize
     * @return int blocksize
     */
    public int getBlockSize() {
        return this.blockdata.getBlocksize();
    }

    /**
     * Gets hash.
     *
     * @param seqnum the seqnum
     * @return the hash
     */
    public ByteString getHash(int seqnum) {
        return this.blockdata.getNexthashes(seqnum);
    }

    /**
     * Gets sig.
     *
     * @return the sig
     */
    public ByteString getSig() {
        return ByteString.copyFrom(this.mSignature);
    }

    /**
     * Get application byte [ ].
     *
     * @return the byte [ ]
     */
    public byte[] getApplication() {
        return this.mApplication;
    }

    /**
     * Gets hash list.
     *
     * @return the hash list
     */
    public List<ByteString> getHashList() {
        return mHashList;
    }

    /**
     * Gets from fingerprint.
     *
     * @return the from fingerprint
     */
    public ByteString getFromFingerprint() {
        return mFromFingerprint;
    }

    /**
     * Gets to fingerprint.
     *
     * @return the to fingerprint
     */
    public ByteString getToFingerprint() {
        return mToFingerprint;
    }

    /**
     * Get signature byte [ ].
     *
     * @return the byte [ ]
     */
    public byte[] getSignature() {
        return mSignature;
    }

    /**
     * Gets session id.
     *
     * @return the session id
     */
    public int getSessionID() {
        return mSessionID;
    }

    /**
     * Gets to disk.
     *
     * @return the to disk
     */
    public boolean getToDisk() {
        return mToDisk;
    }

    /**
     * Sets to disk.
     *
     * @param t the t
     */
    public void setToDisk(boolean t) { this.mToDisk = t; }

    /**
     * New builder builder.
     *
     * @return the builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * The type Builder.
     */
    public static class Builder {
        private boolean todisk;
        private byte[] application;
        private int sessionid;
        public int mBlockSize;
        private ByteString mToFingerprint;
        private ByteString mFromFingerprint;
        private List<ByteString> hashlist;
        private ByteString mSig;

        /**
         * Instantiates a new Builder.
         */
        public Builder() {
            todisk = false;
            sessionid = -1;
            mBlockSize = -1;
        }

        /**
         * Sets the fingerprint for the recipient.
         *
         * @param toFingerprint the to fingerprint
         * @return builder
         */
        public Builder setToFingerprint(ByteString toFingerprint) {
            this.mToFingerprint = toFingerprint;
            return this;
        }

        /**
         * Sets from fingerprint.
         *
         * @param fromFingerprint sets the fingerprint for the sender
         * @return builder
         */
        public Builder setFromFingerprint(ByteString fromFingerprint) {
            this.mFromFingerprint = fromFingerprint;
            return this;
        }

        /**
         * Sets application.
         *
         * @param application bytes for UTF encoded scatterbrain application string
         * @return builder
         */
        public Builder setApplication(byte[] application) {
            this.application = application;
            return this;
        }

        /**
         * Sets to disk.
         *
         * @param toDisk whether to write this file to disk or attempt to store it in the database
         * @return builder
         */
        public Builder setToDisk(boolean toDisk) {
            this.todisk = toDisk;
            return this;
        }

        /**
         * Sets session id.
         *
         * @param sessionID the session id (used for upgrading between protocols)
         * @return builder
         */
        public Builder setSessionID(int sessionID) {
            this.sessionid = sessionID;
            return this;
        }

        /**
         * Sets hashes.
         *
         * @param hashes list of hashes of following blocksequence packets.
         * @return builder
         */
        public Builder setHashes(List<ByteString> hashes) {
            this.hashlist = hashes;
            return this;
        }

        /**
         * Sets blocksize
         * @param blockSize
         * @return builder
         */
        public Builder setBlockSize(int blockSize) {
            this.mBlockSize = blockSize;
            return this;
        }

        public Builder setSig(ByteString sig) {
            this.mSig = sig;
            return this;
        }

        public ByteString getSig() {
            return mSig;
        }

        /**
         * Build block header packet.
         *
         * @return the block header packet
         */
        public BlockHeaderPacket build() {
            if (hashlist == null)
                return null;

            // fingerprints and application are required
            if (mFromFingerprint == null || mToFingerprint == null || application == null) {
                return null;
            }

            if (mBlockSize <= 0) {
                return null;
            }

            return new BlockHeaderPacket(this);
        }

        /**
         * Get application name (in UTF-8).
         *
         * @return the byte [ ]
         */
        public byte[] getApplication() {
            return application;
        }

        /**
         * Gets sessionid.
         *
         * @return the sessionid
         */
        public int getSessionid() {
            return sessionid;
        }

        /**
         * Gets hashlist.
         *
         * @return the hashlist
         */
        public List<ByteString> getHashlist() {
            return hashlist;
        }

        /**
         * Gets to fingerprint.
         *
         * @return the to fingerprint
         */
        public ByteString getmToFingerprint() {
            return mToFingerprint;
        }

        /**
         * Gets from fingerprint.
         *
         * @return the from fingerprint
         */
        public ByteString getmFromFingerprint() {
            return mFromFingerprint;
        }

        /**
         * Gets to disk.
         *
         * @return the to disk
         */
        public boolean getToDisk() {
            return todisk;
        }

        /**
         * Gets blocksize
         * @return blocksize
         */
        public int getBlockSize() { return mBlockSize; }
    }
}
