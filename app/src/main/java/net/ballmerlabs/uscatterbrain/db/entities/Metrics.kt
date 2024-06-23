package net.ballmerlabs.uscatterbrain.db.entities

import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import net.ballmerlabs.scatterbrainsdk.ApiMetrics
import java.util.Date

@Entity
data class Metrics(
    @ColumnInfo
    @PrimaryKey
    var application: String,
    @ColumnInfo
    var messages: Long = 0,
    @ColumnInfo
    var signed: Long = 0,
    @ColumnInfo
    var lastSeen: Long = Date().time
) {
    fun api(): ApiMetrics {
        return ApiMetrics(
            application,
            messages,
            signed,
            lastSeen
        )
    }
}