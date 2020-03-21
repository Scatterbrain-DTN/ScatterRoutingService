package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.Sign;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

public class BlockHeaderPacket implements ScatterSerializable {
    private ScatterProto.BlockData blockdata;
    private List<ByteString> mHashList;
    private ByteString mFromFingerprint;
    private ByteString mToFingerprint;
    private byte[] mSignature;
    private byte[] mApplication;
    private int mSessionID;
    private boolean mToDisk;

    private BlockHeaderPacket(Builder builder) {
        this.mHashList = builder.getHashlist();
        this.mSignature = new byte[Sign.ED25519_BYTES];
        this.mToFingerprint = builder.getmToFingerprint();
        this.mFromFingerprint = builder.getmFromFingerprint();
        this.mHashList = builder.getHashlist();
        this.mApplication = builder.getApplication();
        this.mSessionID = builder.getSessionid();
        this.mToDisk = builder.getToDisk();
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

    private void init(ScatterProto.BlockData data) {
        this.blockdata = data;
        this.mApplication = blockdata.getApplicationBytes().toByteArray();
        this.mHashList = blockdata.getNexthashesList();
        this.mFromFingerprint = blockdata.getFromFingerprint();
        this.mToFingerprint = blockdata.getToFingerprint();
        this.mSignature = blockdata.getSig().toByteArray();
        this.mToDisk = blockdata.getTodisk();
        this.mSessionID = blockdata.getSessionid();
    }

    private void init(InputStream data) throws IOException{
        init(ScatterProto.BlockData.parseDelimitedFrom(data));
    }

    private void init(byte[] data) throws IOException{
        ByteArrayInputStream is = new ByteArrayInputStream(data);
        init(ScatterProto.BlockData.parseDelimitedFrom(is));
    }

    public BlockHeaderPacket(byte[] data)  throws IOException {
        init(data);
    }

    public BlockHeaderPacket(byte[] data, int blocksize) throws IOException {
        init(data);
    }

    public BlockHeaderPacket(InputStream in) throws IOException {
        init(in);
    }

    public void writeToOutputStream(OutputStream out) throws IOException {
        buildBlockData();
        blockdata.writeTo(out);
    }

    @Override
    public byte[] getBytes() {
        buildBlockData();
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
    public boolean writeToStream(OutputStream os) {
        try {
            blockdata.writeTo(os);
        } catch (IOException e) {
            return false;
        }

        return true;
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

    public void setToDisk(boolean t) { this.mToDisk = t; }


    public static class Builder {
        private boolean todisk;
        private byte[] application;
        private int sessionid;
        private ByteString mToFingerprint;
        private ByteString mFromFingerprint;
        private List<ByteString> hashlist;

        public Builder() {
            todisk = false;
            sessionid = -1;
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
            return this;
        }

        public BlockHeaderPacket build() {
            if (hashlist == null)
                return null;

            // fingerprints and application are required
            if (mFromFingerprint == null || mToFingerprint == null || application == null) {
                return null;
            }

            return new BlockHeaderPacket(this);
        }

        public byte[] getApplication() {
            return application;
        }

        public int getSessionid() {
            return sessionid;
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

        public boolean getToDisk() {
            return todisk;
        }
    }
}
