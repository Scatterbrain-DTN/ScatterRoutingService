package com.example.uscatterbrain;

import java.util.Map;
import java.util.Set;

public interface RouterPreferences {

    String DECLARE_HASHES_CAP = "declarehashescap";
    String IDENTITY_CAP = "identitycap";
    String BLOCKDATA_CAP = "blockdatacap";

    boolean getBoolean(String key, boolean def);
    float getFloat(String key, float def);
    public long getLog(String key, long def);
    public int getInt(String key, int def);
    String getString(String key, String def);
    Set<String> getStringSet(String key, Set<String> def);
    Map<String, ?> getAll();
    boolean contains(String key);
}
