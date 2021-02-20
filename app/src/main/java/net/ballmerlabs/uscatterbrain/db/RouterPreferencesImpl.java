package net.ballmerlabs.uscatterbrain.db;

import android.content.SharedPreferences;

import net.ballmerlabs.uscatterbrain.RouterPreferences;

import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class RouterPreferencesImpl implements RouterPreferences {
    private final SharedPreferences preferences;

    @Inject
    public RouterPreferencesImpl(
            SharedPreferences preferences
    ) {
        this.preferences = preferences;
    }

    @Override
    public boolean getBoolean(String key, Boolean def) {
        return Boolean.parseBoolean(preferences.getString(key, def.toString()));
    }

    @Override
    public float getFloat(String key, Float def) {
        return Float.parseFloat(preferences.getString(key, def.toString()));
    }

    @Override
    public String getString(String key, String def) {
        return preferences.getString(key, def);
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> def) {
        return preferences.getStringSet(key, def);
    }

    @Override
    public Map<String, ?> getAll() {
        return preferences.getAll();
    }

    @Override
    public long GetLong(String key, Long def) {
        return Long.parseLong(preferences.getString(key, def.toString()));
    }

    @Override
    public int getInt(String key, Integer def) {
        return Integer.parseInt(preferences.getString(key, def.toString()));
    }

    @Override
    public boolean contains(String key) {
        return preferences.contains(key);
    }
}
