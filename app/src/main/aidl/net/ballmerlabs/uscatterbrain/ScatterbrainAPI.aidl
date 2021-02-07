// ScatterbrainAPI.aidl
package net.ballmerlabs.uscatterbrain;
import net.ballmerlabs.uscatterbrain.API.ScatterMessage;
import net.ballmerlabs.uscatterbrain.API.Identity;

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

    Identity generateIdentity(in String name);

    void authorizeApp(in String identity, in String packagename);

    void deauthorizeApp(in String identity, in String packagename);
}