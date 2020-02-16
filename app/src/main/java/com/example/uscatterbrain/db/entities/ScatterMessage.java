package com.example.uscatterbrain.db.entities;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

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

    public long getMessageID() {
        return messageID;
    }

    public void setMessageID(long messageID) {
        this.messageID = messageID;
    }

    public Identity getIdentity() {
        return identity;
    }
}
