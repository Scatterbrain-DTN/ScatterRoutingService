package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.goterl.lazycode.lazysodium.Sodium;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;
import com.goterl.lazycode.lazysodium.interfaces.Sign;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class BlockDataPacket {
    private ScatterProto.BlockData blockdata;
    private List<ByteString> mHashList;
    private ByteString mFromFingerprint;
    private ByteString mToFingerprint;
    private String mApplication;
    public BlockDataPacket(Builder builder) {
        this.mHashList = builder.getHashlist();
    }

    public boolean verifyed25519(byte[] pubkey) {
        if (pubkey.length != Sign.ED25519_PUBLICKEYBYTES)
            return false;

        ByteString messagebytes = ByteString.EMPTY;
        messagebytes = messagebytes.concat(this.blockdata.getFromFingerprint());
        messagebytes = messagebytes.concat(this.blockdata.getToFingerprint());
        messagebytes = messagebytes.concat(this.blockdata.getApplicationBytes());
        ByteString sessionidBytes = ByteString.copyFrom(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(blockdata.getSessionid()).array());
        messagebytes = messagebytes.concat(sessionidBytes);
        byte td = 0;
        if (this.blockdata.getTodisk())
            td = 1;
        ByteString toDiskBytes = ByteString.copyFrom(ByteBuffer.allocate(1).order(ByteOrder.BIG_ENDIAN).putInt(td).array());
        messagebytes = messagebytes.concat(toDiskBytes);

        for (ByteString hash : this.blockdata.getNexthashesList()) {
            messagebytes = messagebytes.concat(hash);
        }

        return LibsodiumInterface.getSodium().crypto_sign_verify_detached(this.blockdata.getSig().toByteArray(),
                messagebytes.toByteArray(),
                messagebytes.size(),
                pubkey) == 0;
    }

    public BlockDataPacket(byte[] data, int chunksize) throws InvalidProtocolBufferException,
            IOException, InvalidChecksumException {
        InputStream is = new ByteArrayInputStream(data);
        this.blockdata = ScatterProto.BlockData.parseDelimitedFrom(is);
        this.mApplication = blockdata.getApplication();
        this.mHashList = blockdata.getNexthashesList();
        this.mFromFingerprint = blockdata.getFromFingerprint();
        this.mToFingerprint = blockdata.getToFingerprint();

    }

    public BlockDataPacket(InputStream in, int chunksize) throws IOException {
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
        blockdata.writeTo(out);
    }

    public byte[] getBytes() {
        return blockdata.toByteArray();
    }

    public ScatterProto.BlockData getBlockdata() {
        return blockdata;
    }

    public static class InvalidChecksumException extends Exception {

    }

    public ByteString getHash(int seqnum) {
        return this.blockdata.getNexthashes(seqnum);
    }

    public static class Builder {
        private ScatterProto.BlockData.Builder proto;
        private ScatterProto.BlockData blockdata;
        private boolean dataready;
        private int chunksize;
        private boolean todisk;
        private String application;
        private int sessionid;
        private List<ByteString> hashlist;

        public Builder() {

        }

        public Builder setChunkSize(int chunksize) {
            this.chunksize = chunksize;
            return this;
        }

        public Builder setApplication(String application) {
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

        public BlockDataPacket build() {
            return new BlockDataPacket(this);
        }

        public ScatterProto.BlockData.Builder getProto() {
            return proto;
        }

        public ScatterProto.BlockData getBlockdata() {
            return blockdata;
        }

        public boolean isDataready() {
            return dataready;
        }

        public int getChunksize() {
            return chunksize;
        }

        public boolean isTodisk() {
            return todisk;
        }

        public String getApplication() {
            return application;
        }

        public int getSessionid() {
            return sessionid;
        }

        public List<ByteString> getHashlist() {
            return hashlist;
        }
    }
}
