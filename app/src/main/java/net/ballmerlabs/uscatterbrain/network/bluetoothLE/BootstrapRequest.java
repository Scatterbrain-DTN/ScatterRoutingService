package net.ballmerlabs.uscatterbrain.network.bluetoothLE;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.Serializable;

public class BootstrapRequest implements Parcelable {
    private final Bundle extras = new Bundle();
    public static final String TRANSPORT_FROM = "proto-from";
    public static final String TRANSPORT_TO = "proto-to";

    protected BootstrapRequest() {

    }

    protected BootstrapRequest(Parcel in) {
        extras.readFromParcel(in);
    }

    public static final Creator<BootstrapRequest> CREATOR = new Creator<BootstrapRequest>() {
        @Override
        public BootstrapRequest createFromParcel(Parcel in) {
            return new BootstrapRequest(in);
        }

        @Override
        public BootstrapRequest[] newArray(int size) {
            return new BootstrapRequest[size];
        }
    };

    @Override
    public int describeContents() {
        return extras.describeContents();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBundle(extras);
    }

    public <T extends Parcelable> void putParcelableExtra(String key, T parcelable) {
        extras.putParcelable(key, parcelable);
    }

    public <T extends Parcelable> T getParcelableExtra(String key) {
        return extras.getParcelable(key);
    }

    public void putStringExtra(String key, String val) {
        extras.putString(key, val);
    }

    public String getStringExtra(String key) {
        return extras.getString(key);
    }

    public void putSerializableExtra(String key, Serializable val) {
        extras.putSerializable(key, val);
    }

    public Serializable getSerializableExtra(String key) {
        return extras.getSerializable(key);
    }
}
