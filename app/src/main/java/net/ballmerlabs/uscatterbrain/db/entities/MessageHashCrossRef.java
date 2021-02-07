package net.ballmerlabs.uscatterbrain.db.entities;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(primaryKeys = {"messageID", "hashID"}, indices = {@Index("messageID"), @Index("hashID")})
public class MessageHashCrossRef {
    @ColumnInfo(name = "messageID")
    public long messageID;

    @ColumnInfo(name = "hashID")
    public long hashID;
}
