package com.turbodns.changer.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.turbodns.changer.model.DnsPreset;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PresetStorage {
    private static final String PREF_NAME = "turbo_dns_presets";
    private static final String KEY_PRESETS_JSON = "presets_json";
    private static final String KEY_ACTIVE_PRESET_ID = "active_preset_id";

    private final SharedPreferences prefs;

    public PresetStorage(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public List<DnsPreset> getAllPresets() {
        List<DnsPreset> presets = new ArrayList<>();
        String jsonString = prefs.getString(KEY_PRESETS_JSON, null);

        if (jsonString == null || jsonString.isEmpty()) {
            return presets;
        }

        try {
            JSONArray array = new JSONArray(jsonString);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String id = obj.optString("id", String.valueOf(System.currentTimeMillis()));
                String name = obj.optString("name", "Custom Preset");
                JSONArray serversArray = obj.optJSONArray("dnsServers");

                List<String> servers = new ArrayList<>();
                if (serversArray != null) {
                    for (int j = 0; j < serversArray.length(); j++) {
                        servers.add(serversArray.getString(j));
                    }
                }
                presets.add(new DnsPreset(id, name, servers));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return presets;
    }

    public void savePresets(List<DnsPreset> presets) {
        JSONArray array = new JSONArray();
        for (DnsPreset preset : presets) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", preset.getId());
                obj.put("name", preset.getName());
                JSONArray serversArray = new JSONArray();
                for (String server : preset.getDnsServers()) {
                    serversArray.put(server);
                }
                obj.put("dnsServers", serversArray);
                array.put(obj);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        prefs.edit().putString(KEY_PRESETS_JSON, array.toString()).apply();
    }

    public void savePreset(DnsPreset preset) {
        List<DnsPreset> presets = getAllPresets();
        int existingIndex = -1;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).getId().equals(preset.getId())) {
                existingIndex = i;
                break;
            }
        }

        if (existingIndex >= 0) {
            presets.set(existingIndex, preset);
        } else {
            presets.add(preset);
        }
        savePresets(presets);
    }

    public void deletePreset(String presetId) {
        List<DnsPreset> presets = getAllPresets();
        List<DnsPreset> updated = new ArrayList<>();
        for (DnsPreset p : presets) {
            if (!p.getId().equals(presetId)) {
                updated.add(p);
            }
        }
        savePresets(updated);
    }

    public String getActivePresetId() {
        return prefs.getString(KEY_ACTIVE_PRESET_ID, "");
    }

    public void setActivePresetId(String id) {
        prefs.edit().putString(KEY_ACTIVE_PRESET_ID, id).apply();
    }

    public DnsPreset getActivePreset() {
        String activeId = getActivePresetId();
        List<DnsPreset> presets = getAllPresets();
        for (DnsPreset p : presets) {
            if (p.getId().equals(activeId)) {
                return p;
            }
        }
        return presets.isEmpty() ? null : presets.get(0);
    }
}
