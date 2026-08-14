package com.turbodns.changer.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.turbodns.changer.R;
import com.turbodns.changer.model.DnsPreset;
import com.turbodns.changer.storage.PresetStorage;

import java.util.ArrayList;
import java.util.List;

public class PresetEditorActivity extends Activity {

    public static final String EXTRA_PRESET_ID = "preset_id";

    private EditText etPresetName;
    private EditText etDnsIps;
    private Button btnSave;
    private Button btnDelete;

    private PresetStorage storage;
    private DnsPreset currentPreset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preset_editor);

        storage = new PresetStorage(this);

        etPresetName = findViewById(R.id.etPresetName);
        etDnsIps = findViewById(R.id.etDnsIps);
        btnSave = findViewById(R.id.btnSave);
        btnDelete = findViewById(R.id.btnDelete);

        String presetId = getIntent().getStringExtra(EXTRA_PRESET_ID);
        if (presetId != null) {
            List<DnsPreset> presets = storage.getAllPresets();
            for (DnsPreset p : presets) {
                if (p.getId().equals(presetId)) {
                    currentPreset = p;
                    break;
                }
            }
        }

        if (currentPreset != null) {
            etPresetName.setText(currentPreset.getName());
            StringBuilder sb = new StringBuilder();
            for (String ip : currentPreset.getDnsServers()) {
                sb.append(ip).append("\n");
            }
            etDnsIps.setText(sb.toString().trim());
        } else {
            currentPreset = new DnsPreset();
            btnDelete.setVisibility(View.GONE);
        }

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePreset();
            }
        });

        btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentPreset != null && currentPreset.getId() != null) {
                    storage.deletePreset(currentPreset.getId());
                    Toast.makeText(PresetEditorActivity.this, "Preset deleted", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        });
    }

    private void savePreset() {
        String name = etPresetName.getText().toString().trim();
        String ipsRaw = etDnsIps.getText().toString().trim();

        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter a preset name", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] lines = ipsRaw.split("\n");
        List<String> servers = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                servers.add(trimmed);
            }
        }

        if (servers.isEmpty()) {
            Toast.makeText(this, "Please enter at least one DNS IPv4 or IPv6 address", Toast.LENGTH_SHORT).show();
            return;
        }

        currentPreset.setName(name);
        currentPreset.setDnsServers(servers);
        storage.savePreset(currentPreset);

        Toast.makeText(this, "Preset saved successfully", Toast.LENGTH_SHORT).show();
        finish();
    }
}
