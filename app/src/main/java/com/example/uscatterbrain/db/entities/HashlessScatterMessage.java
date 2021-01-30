package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.Insert;
import androidx.room.PrimaryKey;

import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.List;


@Entity(
        tableName = "messages",
        indices = {
                @Index(value =  {"filepath"}, unique = true),
                @Index(value = {"identity_fingerprint"}, unique = true),
                @Index(value = {"globalhash"}, unique = true)
        }
        )
public class HashlessScatterMessage {

    public HashlessScatterMessage() {
        this.body = null;
    }

    public HashlessScatterMessage(KeylessIdentity identity, byte[] body) {
        this.body = body;
    }

    public static List<Hashes> hash2hashs(List<ByteString> hashes) {
        final ArrayList<Hashes> result = new ArrayList<>();
        for (ByteString hash : hashes) {
            Hashes h = new Hashes();
            h.hash = hash.toByteArray();
            result.add(h);
        }
        return result;
    }

    public static List<ByteString> hashes2hash(List<Hashes> hashes) {
        final ArrayList<ByteString> result = new ArrayList<>();
        for (Hashes hash : hashes) {
            result.add(ByteString.copyFrom(hash.hash));
        }
        return result;
    }

    @PrimaryKey(autoGenerate = true)
    public long messageID;

    @ColumnInfo
    public byte[] body;

    @ColumnInfo
    public String identity_fingerprint;

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

    @ColumnInfo(name = "globalhash")
    public byte[] globalhash;

    @ColumnInfo
    public String userFilename;

    @ColumnInfo
    public String mimeType;
}
