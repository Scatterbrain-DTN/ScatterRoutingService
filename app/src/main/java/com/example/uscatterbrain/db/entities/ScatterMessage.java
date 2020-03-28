package com.example.uscatterbrain.db.entities;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

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

    @PrimaryKey(autoGenerate = true)
    private long messageID;

    @ColumnInfo
    private byte[] body;

    @ColumnInfo
    @ForeignKey(entity = Identity.class, parentColumns = "identityID", childColumns = "identityID")
    @NonNull
    private long identityID;

    @ColumnInfo
    private byte[] to;

    @ColumnInfo
    private String application;

    @ColumnInfo
    private byte[] sig;

    @Ignore
    private List<Hashes> hashes = new ArrayList<>();

    @Ignore
    private List<DiskFiles> files = new ArrayList<>();

    @Ignore
    private Identity identity;

    public byte[] getBody() {
        return body;
    }

    public List<DiskFiles> getFiles() {
        return files;
    }

    public void addFile(DiskFiles files) {
        this.files.add(files);
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

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
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

    public Identity getIdentity() {
        return identity;
    }
}
