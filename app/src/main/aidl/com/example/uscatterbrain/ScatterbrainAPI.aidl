// ScatterbrainAPI.aidl
package com.example.uscatterbrain;
import com.example.uscatterbrain.API.ScatterMessage;
import com.example.uscatterbrain.API.Identity;

interface ScatterbrainAPI {

    List<ScatterMessage> getByApplication(String application);

    ScatterMessage getById(long id);

    List<Identity> getIdentities();

    Identity getIdentityByFingerprint(in String fingerprint);

    void sendMessage(in ScatterMessage message);

    void sendMessages(in List<ScatterMessage> messages);

    void startDiscovery();

    void stopDiscovery();

    void startPassive();

    void stopPassive();
}