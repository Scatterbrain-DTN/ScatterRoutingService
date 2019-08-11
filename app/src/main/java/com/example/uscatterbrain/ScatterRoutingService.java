package com.example.uscatterbrain;

import android.content.SharedPreferences;

import com.example.uscatterbrain.API.HighLevelAPI;
import com.example.uscatterbrain.API.OnRecieveCallback;
import com.example.uscatterbrain.API.ScatterTransport;
import com.example.uscatterbrain.network.BlockDataPacket;

import java.io.InputStream;

public class ScatterRoutingService implements HighLevelAPI {
    //system
    @Override
    public void startService() {

    }

    @Override
    public void stopService() {

    }

    @Override
    public SharedPreferences getPref() {
        return null;
    }

    @Override
    public void setPref(SharedPreferences pref) {

    }

    @Override
    public DeviceProfile getProfile() {
        return null;
    }

    @Override
    public void setProfile(DeviceProfile prof) {

    }

    @Override
    public ScatterTransport[] getTransports() {
        return null;
    }

    @Override
    public String getScatterApplication() {
        return null;
    }

    @Override
    public void setScatterApplication(String application) {

    }


    //peers
    @Override
    public void scanOn(ScatterTransport transport) {

    }

    @Override
    public void scanOff(ScatterTransport transport) {

    }

    @Override
    public DeviceProfile[] getPeers() {
        return null;
    }

    //communications
    @Override
    public boolean sendDataDirected(DeviceProfile target, byte[] data) {
        return false;
    }

    @Override
    public void sendDataMulticast(byte[] data) {

    }

    @Override
    public boolean sendFileDirected(DeviceProfile target, InputStream file, String name, long len) {
        return false;
    }

    @Override
    public void sendFileMulticast(InputStream file, String name, long len) {

    }

    @Override
    public void registerOnRecieveCallback(OnRecieveCallback callback) {

    }

    //datastore
    @Override
    public BlockDataPacket[] getTopMessages(int num) {
        return null;
    }

    @Override
    public BlockDataPacket[] getRandomMessages(int num) {
        return null;
    }

    //datastore systems tasks

    @Override
    public void flushDatastore() {

    }

    @Override
    public void setDatastoreLimit(int limit) {

    }

    @Override
    public int getDatastoreLimit() {
        return 0;
    }
}
