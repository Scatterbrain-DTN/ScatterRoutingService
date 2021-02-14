package net.ballmerlabs.uscatterbrain;

import java.util.Map;
import java.util.Set;

public interface RouterPreferences {
    boolean getBoolean(String key, boolean def);
    float getFloat(String key, Float def);
    public long GetLong(String key, Long def);
    public int getInt(String key, Integer def);
    String getString(String key, String def);
    Set<String> getStringSet(String key, Set<String> def);
    Map<String, ?> getAll();
    boolean contains(String key);
}
