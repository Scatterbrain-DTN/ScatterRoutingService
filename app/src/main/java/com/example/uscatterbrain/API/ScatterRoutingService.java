package com.example.uscatterbrain.API;

import android.content.SharedPreferences;


import com.example.uscatterbrain.DeviceProfile;
import com.example.uscatterbrain.network.BlockHeaderPacket;

import java.io.InputStream;

/**
 * basic interface to the Scatterbrain protocol, to be used by
 * external applications
 */

public interface ScatterRoutingService {
    int PROTOVERSION = 0;

    //system
    void startService();
    void stopService();
    SharedPreferences getPref();
    void setPref(SharedPreferences pref);
    DeviceProfile getProfile();
    void setProfile(DeviceProfile prof);
    ScatterTransport[] getTransports();
    String getScatterApplication();
    void setScatterApplication(String application);

    //peers
    void scanOn(ScatterTransport transport);
    void scanOff(ScatterTransport transport);
    void advertiseOn();
    void advertiseOff();
    DeviceProfile[] getPeers();

    //communications
    boolean sendDataDirected(DeviceProfile target, byte[] data);
    void sendDataMulticast(byte[] data);
    boolean sendFileDirected(DeviceProfile target, InputStream file, String name, long len);
    void sendFileMulticast(InputStream file, String name, long len); //TODO: java file object
    void registerOnRecieveCallback(OnRecieveCallback callback);


    //datastore
    BlockHeaderPacket[] getTopMessages(int num);
    BlockHeaderPacket[] getRandomMessages(int num);

    //datastore systems tasks
    void flushDatastore();
    void setDatastoreLimit(int limit);
    int getDatastoreLimit();
}
