package net.ballmerlabs.uscatterbrain.network.wifidirect;

import android.os.Parcel;

import net.ballmerlabs.uscatterbrain.network.AdvertisePacket;
import net.ballmerlabs.uscatterbrain.network.UpgradePacket;
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest;

public class WifiDirectBootstrapRequest extends BootstrapRequest {
    private final String name;
    private final String passphrase;
    private final BluetoothLEModule.ConnectionRole role;
    public static final String DEFAULT_NAME = "DIRECT-scatterbrain";
    public static final String KEY_NAME = "p2p-groupname";
    public static final String KEY_PASSPHRASE = "p2p-passphrase";
    public static final String KEY_ROLE = "p2p-role";
    protected WifiDirectBootstrapRequest(Parcel in) {
        super(in);
        name = getStringExtra(KEY_NAME);
        passphrase = getStringExtra(KEY_PASSPHRASE);
        role = (BluetoothLEModule.ConnectionRole) getSerializableExtra(KEY_ROLE);
    }

    private WifiDirectBootstrapRequest(String passphrase, BluetoothLEModule.ConnectionRole role) {
        super();
        this.passphrase = passphrase;
        this.name = "DIRECT-scatterbrain";
        this.role = role;
        putStringExtra(KEY_NAME, name);
        putStringExtra(KEY_PASSPHRASE, passphrase);
        putSerializableExtra(KEY_ROLE, role);
    }

    private WifiDirectBootstrapRequest(String passphrase, String name, BluetoothLEModule.ConnectionRole role) {
        super();
        this.passphrase = passphrase;
        this.name = name;
        this.role = role;
        putStringExtra(KEY_NAME, name);
        putStringExtra(KEY_PASSPHRASE, passphrase);
        putSerializableExtra(KEY_ROLE, role);
    }

    public static WifiDirectBootstrapRequest create(String passphrase, BluetoothLEModule.ConnectionRole role) {
        return new WifiDirectBootstrapRequest(passphrase, role);
    }

    public static WifiDirectBootstrapRequest create(UpgradePacket packet, BluetoothLEModule.ConnectionRole role) {
        if (!packet.getProvides().equals(AdvertisePacket.Provides.WIFIP2P)) {
            throw new IllegalStateException("WifiDirectBootstrapRequest called with invalid provides");
        }

        String name = packet.getMetadata().get(KEY_NAME);
        if (name ==  null) {
            throw new IllegalArgumentException("name was null");
        }
        String passphrase = packet.getMetadata().get(KEY_PASSPHRASE);
        if (passphrase == null) {
            throw new IllegalArgumentException("passphrase was null");
        }


        return new WifiDirectBootstrapRequest(passphrase, name, role);
    }

    public String getName() {
        return name;
    }

    public String getPassphrase() {
        return passphrase;
    }

    public BluetoothLEModule.ConnectionRole getRole() {
        return role;
    }
}
