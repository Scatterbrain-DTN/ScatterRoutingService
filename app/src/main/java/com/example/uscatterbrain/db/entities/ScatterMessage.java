package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.Hash;

import java.util.ArrayList;
import java.util.List;


@Entity(tableName = "messages")
public class ScatterMessage {

    public ScatterMessage() {
        this.identity = null;
        this.body = null;
    }

    public ScatterMessage(Identity identity, byte[] body) {
        this.identity = identity;
        this.body = body;
    }

    public static List<Hashes> hash2hashs(List<ByteString> hashes) {
        final ArrayList<Hashes> result = new ArrayList<>();
        for (ByteString hash : hashes) {
            Hashes h = new Hashes();
            h.setHash(hash.toByteArray());
            result.add(h);
        }
        return result;
    }

    public static List<ByteString> hashes2hash(List<Hashes> hashes) {
        final ArrayList<ByteString> result = new ArrayList<>();
        for (Hashes hash : hashes) {
            result.add(ByteString.copyFrom(hash.getHash()));
        }
        return result;
    }

    @PrimaryKey(autoGenerate = true)
    private long messageID;

    @ColumnInfo
    private byte[] body;

    @ColumnInfo
    @ForeignKey(entity = Identity.class, parentColumns = "identityID", childColumns = "identityID")
    private long identityID;

    @ColumnInfo
    private byte[] to;

    @ColumnInfo
    private byte[] from;

    @ColumnInfo
    private byte[] application;

    @ColumnInfo
    private byte[] sig;

    @ColumnInfo
    private int sessionid;

    @ColumnInfo
    private int blocksize;

    @ColumnInfo
    String filePath;

    @Ignore
    private List<Hashes> hashes = new ArrayList<>();

    @Ignore
    private Identity identity;

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    public void setIdentityID(long identityID) {
        this.identityID = identityID;
    }

    public long getIdentityID() {
        return identityID;
    }

    public byte[] getTo() {
        return to;
    }

    public void setTo(byte[] to) {
        this.to = to;
    }

    public byte[] getApplication() {
        return application;
    }

    public void setApplication(byte[] application) {
        this.application = application;
    }

    public List<Hashes> getHashes() {
        return hashes;
    }

    public void setHashes(List<Hashes> hashes) {
        this.hashes = hashes;
    }

    public long getMessageID() {
        return messageID;
    }

    public void setMessageID(long messageID) {
        this.messageID = messageID;
    }

    public byte[] getSig() {
        return sig;
    }

    public void setSig(byte[] sig) {
        this.sig = sig;
    }

    public byte[] getFrom() {
        return from;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFrom(byte[] from) {
        this.from = from;
    }

    public int getSessionid() {
        return sessionid;
    }

    public void setSessionid(int sessionid) {
        this.sessionid = sessionid;
    }

    public void setIdentity(Identity identity) {
        this.identity = identity;
    }

    public void setFilePath(String file) {
        this.filePath = file;
    }

    public int getBlocksize() {
        return blocksize;
    }

    public void setBlocksize(int blocksize) {
        this.blocksize = blocksize;
    }

    public Identity getIdentity() {
        return identity;
    }
}
