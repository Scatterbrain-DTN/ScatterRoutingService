package com.example.uscatterbrain.network;

import androidx.annotation.NonNull;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.goterl.lazycode.lazysodium.Sodium;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;
import com.goterl.lazycode.lazysodium.interfaces.Sign;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.function.Consumer;

public class BlockDataPacket implements Iterable<BlockSequencePacket>,
        Iterator<BlockSequencePacket> {
    private ScatterProto.BlockData blockdata;
    private List<ByteString> mHashList;
    private ByteString mFromFingerprint;
    private ByteString mToFingerprint;
    private byte[] mSignature;
    private byte[] mApplication;
    private int mSessionID;
    private boolean mToDisk;
    private boolean mBuildOnFly;
    private int mBlocksize;
    private int mSize;
    private int mIndex;
    private InputStream mFragmentStream;
    public static final int DEFAULT_BLOCK_SIZE = 1024*1024*1024;
    public static final long MAX_SIZE_NONFILE = 512*1024;

    private BlockDataPacket(Builder builder) {
        this.mHashList = builder.getHashlist();
        this.mSignature = new byte[Sign.ED25519_BYTES];
        this.mToFingerprint = builder.getmToFingerprint();
        this.mFromFingerprint = builder.getmFromFingerprint();
        this.mHashList = builder.getHashlist();
        this.mApplication = builder.getApplication();
        this.mToDisk = builder.isTodisk();
        this.mSessionID = builder.getSessionid();
        this.mSize = builder.getSize();
        this.mBlocksize = builder.getBlockSize();
        this.mFragmentStream = builder.getFragmentStream();
        this.mIndex = 0;

        if (mHashList == null) {
            mBuildOnFly = true;
            mHashList = new ArrayList<>();
        } else {
            mBuildOnFly = false;
        }
    }

    private void buildBlockData() {
        if (this.mSignature == null) {
            this.mSignature = new byte[1];
        }
        if (this.blockdata == null) {
            this.blockdata = ScatterProto.BlockData.newBuilder()
                    .setApplicationBytes(ByteString.copyFrom(this.mApplication))
                    .setFromFingerprint(this.mFromFingerprint)
                    .setToFingerprint(this.mToFingerprint)
                    .setTodisk(this.mToDisk)
                    .addAllNexthashes(this.mHashList)
                    .setSessionid(this.mSessionID)
                    .setSig(ByteString.copyFrom(this.mSignature))
                    .build();
        }
    }

    private ByteString sumBytes() {
        ByteString messagebytes = ByteString.EMPTY;
        messagebytes = messagebytes.concat(this.mFromFingerprint);
        messagebytes = messagebytes.concat(this.mToFingerprint);
        messagebytes = messagebytes.concat(ByteString.copyFrom(this.mApplication));
        ByteString sessionidBytes = ByteString.copyFrom(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(this.mSessionID).array());
        messagebytes = messagebytes.concat(sessionidBytes);
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

    public boolean verifyed25519(byte[] pubkey) {
        if (pubkey.length != Sign.PUBLICKEYBYTES)
            return false;

        ByteString messagebytes = sumBytes();
        return LibsodiumInterface.getSodium().crypto_sign_verify_detached(this.blockdata.getSig().toByteArray(),
                messagebytes.toByteArray(),
                messagebytes.size(),
                pubkey) == 0;
    }

    public boolean signEd25519(byte[] secretkey) {
        if (secretkey.length != Sign.SECRETKEYBYTES)
            return false;

        ByteString messagebytes = sumBytes();

        this.mSignature = new byte[Sign.ED25519_BYTES];
        Pointer p = new PointerByReference(Pointer.NULL).getPointer();
        if (LibsodiumInterface.getSodium().crypto_sign_detached(this.mSignature,
                p, messagebytes.toByteArray(), messagebytes.size(), secretkey) == 0) {
            buildBlockData();
            return true;
        } else {
            return false;
        }
    }

    private void init(byte[] data) throws IOException {
        this.blockdata = ScatterProto.BlockData.parseFrom(data);
        this.mApplication = blockdata.getApplicationBytes().toByteArray();
        this.mHashList = blockdata.getNexthashesList();
        this.mFromFingerprint = blockdata.getFromFingerprint();
        this.mToFingerprint = blockdata.getToFingerprint();
        this.mSignature = blockdata.getSig().toByteArray();
        this.mToDisk = blockdata.getTodisk();
        this.mSessionID = blockdata.getSessionid();
    }

    public BlockDataPacket(byte[] data)  throws IOException {
        this.mBlocksize = DEFAULT_BLOCK_SIZE;
        init(data);
    }

    public BlockDataPacket(byte[] data, int blocksize) throws IOException {
        this.mBlocksize = blocksize;
        init(data);
    }

    public BlockDataPacket(InputStream in) throws IOException {
        blockdata = ScatterProto.BlockData.parseFrom(in);
    }

    public boolean verifySequence(List<BlockSequencePacket> seqlist) {
        for (BlockSequencePacket s : seqlist) {
            if (!s.verifyHash(this))
                return false;
        }
        return true;
    }

    public void writeToOutputStream(OutputStream out) throws IOException {
        buildBlockData();
        blockdata.writeTo(out);
    }

    public byte[] getBytes() {
        buildBlockData();
        return blockdata.toByteArray();
    }

    public ScatterProto.BlockData getBlockdata() {
        buildBlockData();
        return this.blockdata;
    }

    public ByteString getHash(int seqnum) {
        return this.blockdata.getNexthashes(seqnum);
    }

    public ByteString getSig() {
        return ByteString.copyFrom(this.mSignature);
    }

    public byte[] getApplication() {
        return this.mApplication;
    }

    public List<ByteString> getHashList() {
        return mHashList;
    }

    public ByteString getFromFingerprint() {
        return mFromFingerprint;
    }

    public ByteString getToFingerprint() {
        return mToFingerprint;
    }

    public byte[] getSignature() {
        return mSignature;
    }

    public int getSessionID() {
        return mSessionID;
    }

    public boolean getToDisk() {
        return mToDisk;
    }

    @NonNull
    @Override
    public Iterator<BlockSequencePacket> iterator() {
        return this;
    }

    @Override
    public void forEach(@NonNull Consumer<? super BlockSequencePacket> action) {
        Objects.requireNonNull(action);
        for (BlockSequencePacket packet : this) {
            action.accept(packet);
        }
    }

    @NonNull
    @Override
    public Spliterator<BlockSequencePacket> spliterator() {
        return Spliterators.spliterator(iterator(), mHashList.size(),Spliterator.ORDERED
        | Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.SIZED);
    }

    @Override
    public boolean hasNext() {
        return mIndex < mSize;
    }

    @Override
    public BlockSequencePacket next() {
        try {

            // super hacky limited/capped inputstream. Only lets us read up to mBlocksize
            InputStream is = new CappedInputStream(mFragmentStream, mBlocksize);
            ByteString data =  ByteString.readFrom(is);
            BlockSequencePacket bs = new BlockSequencePacket.Builder()
                    .setData(data)
                    .setSequenceNumber(mIndex)
                    .build();

            if (mBuildOnFly) {
                mHashList.add(bs.calculateHashByteString());
            }

            mIndex++;
            return bs;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void remove() {

    }

    @Override
    public void forEachRemaining(@NonNull Consumer<? super BlockSequencePacket> action) {
        Objects.requireNonNull(action);
        for (BlockSequencePacket packet : this) {
            action.accept(packet);
        }
    }

    public static class Builder {
        private boolean todisk;
        private byte[] application;
        private int sessionid;
        private ByteString mToFingerprint;
        private ByteString mFromFingerprint;
        private List<ByteString> hashlist;
        private InputStream mFragmentStream;
        private int mSize;
        private int mBlockSize;

        public Builder() {
            mSize = 0;
            todisk = false;
            sessionid = -1;
            mBlockSize = DEFAULT_BLOCK_SIZE;
        }

        public Builder setToFingerprint(ByteString toFingerprint) {
            this.mToFingerprint = toFingerprint;
            return this;
        }

        public Builder setFromFingerprint(ByteString fromFingerprint) {
            this.mFromFingerprint = fromFingerprint;
            return this;
        }

        public Builder setApplication(byte[] application) {
            this.application = application;
            return this;
        }

        public Builder setToDisk(boolean toDisk) {
            this.todisk = toDisk;
            return this;
        }

        public Builder setSessionID(int sessionID) {
            this.sessionid = sessionID;
            return this;
        }

        public Builder setHashes(List<ByteString> hashes) {
            this.hashlist = hashes;
            this.mSize = hashes.size();
            return this;
        }

        public Builder setSize(int size) {
            this.mSize = size;
            return this;
        }

        public Builder setFragmentStream(InputStream fragmentStream) {
            this.mFragmentStream = fragmentStream;
            return this;
        }

        public BlockDataPacket build() {
            // fail if we have no hashlist and can't build one
            if (hashlist == null && mFragmentStream == null) {
                return null;
            }

            // We have to either use the hashlist size OR set the size manually
            if ((hashlist != null) &&  (hashlist.size() != mSize)) {
                return null;
            }

            // fingerprints and application are required
            if (mFromFingerprint == null || mToFingerprint == null || application == null) {
                return null;
            }

            // Make sure that we don't exceed that maximum size for diskless messages
            // TODO: for messages with 1 sequence packet this should be checked elsewhere
            if (!todisk && mSize > 1 && (mSize * mBlockSize) > MAX_SIZE_NONFILE ) {
                return  null;
            }

            return new BlockDataPacket(this);
        }

        public boolean isTodisk() {
            return todisk;
        }

        public byte[] getApplication() {
            return application;
        }

        public int getSessionid() {
            return sessionid;
        }

        public int getSize() {
            return mSize;
        }

        public int getBlockSize() {
            return mBlockSize;
        }

        public List<ByteString> getHashlist() {
            return hashlist;
        }

        public ByteString getmToFingerprint() {
            return mToFingerprint;
        }

        public ByteString getmFromFingerprint() {
            return mFromFingerprint;
        }

        public InputStream getFragmentStream() {
            return mFragmentStream;
        }
    }
}
