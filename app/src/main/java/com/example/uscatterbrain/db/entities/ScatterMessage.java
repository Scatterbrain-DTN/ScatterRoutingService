package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.List;


@Entity(
        tableName = "messages",
        indices = {@Index(value =  {"filepath"}, unique = true)}
        )
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
    public long messageID;

    @ColumnInfo
    public byte[] body;

    @ColumnInfo
    @ForeignKey(entity = Identity.class, parentColumns = "identityID", childColumns = "identityID")
    public Long identityID;

    @ColumnInfo
    public byte[] to;

    @ColumnInfo
    public byte[] from;

    @ColumnInfo
    public byte[] application;

    @ColumnInfo
    public byte[] sig;

    @ColumnInfo
    public int sessionid;

    @ColumnInfo
    public int blocksize;

    @ColumnInfo
    public String extension;

    @ColumnInfo(name = "filepath")
    public String filePath;

    @ColumnInfo
    public String userFilename;

    @ColumnInfo
    public String mimeType;

    @Ignore
    public List<Hashes> hashes = new ArrayList<>();

    @Ignore
    public Identity identity;
}
