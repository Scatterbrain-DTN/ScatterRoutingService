package com.example.uscatterbrain.db;

import android.content.SharedPreferences;

import com.example.uscatterbrain.RouterPreferences;

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
    public boolean getBoolean(String key, boolean def) {
        return preferences.getBoolean(key, def);
    }

    @Override
    public float getFloat(String key, float def) {
        return preferences.getFloat(key, def);
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
    public long getLog(String key, long def) {
        return preferences.getLong(key, def);
    }

    @Override
    public int getInt(String key, int def) {
        return preferences.getInt(key, def);
    }

    @Override
    public boolean contains(String key) {
        return preferences.contains(key);
    }
}
