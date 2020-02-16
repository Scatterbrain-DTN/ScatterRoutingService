package com.example.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "diskfiles"
)
public class DiskFiles {

    public DiskFiles() {

    }

    @PrimaryKey(autoGenerate = true)
    private long fileID;

    @ColumnInfo
    private int ownerID;

    @ColumnInfo
    private String filepath;

    @ColumnInfo
    private byte[] sha256;

    public byte[] getSha256() {
        return sha256;
    }

    public int getOwnerID() {
        return ownerID;
    }

    public long getFileID() {
        return fileID;
    }

    public String getFilepath() {
        return filepath;
    }

    public void setFileID(long fileID) {
        this.fileID = fileID;
    }

    public void setFilepath(String filepath) {
        this.filepath = filepath;
    }

    public void setOwnerID(int ownerID) {
        this.ownerID = ownerID;
    }

    public void setSha256(byte[] sha256) {
        this.sha256 = sha256;
    }
}
