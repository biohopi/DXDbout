package com.turbodns.changer.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import com.turbodns.changer.R;
import com.turbodns.changer.engine.DnsServerStats;
import com.turbodns.changer.engine.ParallelDnsResolver;
import com.turbodns.changer.engine.RootDnsEngine;
import com.turbodns.changer.model.DnsPreset;
import com.turbodns.changer.service.TurboVpnService;
import com.turbodns.changer.storage.PresetStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQUEST_VPN_PERMISSION = 101;

    private TabHost tabHost;
    private Spinner spinnerMode;
    private Spinner spinnerPresets;
    private Button btnToggle;
    private TextView tvStatus;
    private TextView tvStats;

    private Button btnNewPreset;
    private ListView lvPresets;

    private PresetStorage storage;
    private List<DnsPreset> presetList = new ArrayList<>();
    private DnsPreset selectedPreset;

    private boolean isRootMode = false;
    private boolean isRootActive = false;

    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            updateLiveStats();
            statsHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        storage = new PresetStorage(this);

        setupTabs();

        spinnerMode = findViewById(R.id.spinnerMode);
        spinnerPresets = findViewById(R.id.spinnerPresets);
        btnToggle = findViewById(R.id.btnToggle);
        tvStatus = findViewById(R.id.tvStatus);
        tvStats = findViewById(R.id.tvStats);

        btnNewPreset = findViewById(R.id.btnNewPreset);
        lvPresets = findViewById(R.id.lvPresets);

        setupModeSpinner();

        btnNewPreset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, PresetEditorActivity.class);
                startActivity(intent);
            }
        });

        lvPresets.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < presetList.size()) {
                    DnsPreset presetToEdit = presetList.get(position);
                    Intent intent = new Intent(MainActivity.this, PresetEditorActivity.class);
                    intent.putExtra(PresetEditorActivity.EXTRA_PRESET_ID, presetToEdit.getId());
                    startActivity(intent);
                }
            }
        });

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleService();
            }
        });
    }

    private void setupTabs() {
        tabHost = findViewById(R.id.tabHost);
        tabHost.setup();

        TabHost.TabSpec spec1 = tabHost.newTabSpec("TabDashboard");
        spec1.setContent(R.id.tabDashboard);
        spec1.setIndicator("Dashboard");
        tabHost.addTab(spec1);

        TabHost.TabSpec spec2 = tabHost.newTabSpec("TabPresets");
        spec2.setContent(R.id.tabPresets);
        spec2.setIndicator("Presets");
        tabHost.addTab(spec2);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPresets();
        updateUiState();
        statsHandler.post(statsRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        statsHandler.removeCallbacks(statsRunnable);
    }

    private void setupModeSpinner() {
        List<String> modes = new ArrayList<>();
        modes.add("VpnService Mode (Standard)");
        modes.add("Root Mode (System Direct)");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode.setAdapter(adapter);

        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                isRootMode = (position == 1);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void refreshPresets() {
        presetList = storage.getAllPresets();
        setupPresetSpinner();
        setupPresetsListView();
    }

    private void setupPresetSpinner() {
        List<String> names = new ArrayList<>();
        int selectedIndex = 0;
        String activeId = storage.getActivePresetId();

        for (int i = 0; i < presetList.size(); i++) {
            DnsPreset p = presetList.get(i);
            names.add(p.getName() + " (" + p.getDnsServers().size() + " IPs)");
            if (p.getId().equals(activeId)) {
                selectedIndex = i;
            }
        }

        if (presetList.isEmpty()) {
            names.add("No custom presets (Go to Presets tab)");
            selectedPreset = null;
        } else {
            selectedPreset = presetList.get(Math.min(selectedIndex, presetList.size() - 1));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPresets.setAdapter(adapter);
        spinnerPresets.setSelection(selectedIndex);

        spinnerPresets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < presetList.size()) {
                    selectedPreset = presetList.get(position);
                    storage.setActivePresetId(selectedPreset.getId());
                } else {
                    selectedPreset = null;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupPresetsListView() {
        List<String> presetDisplayList = new ArrayList<>();
        for (DnsPreset p : presetList) {
            presetDisplayList.add(p.getName() + "\n" + p.getDnsServers().size() + " DNS servers (Tap to edit)");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, presetDisplayList);
        lvPresets.setAdapter(adapter);
    }

    private void toggleService() {
        if (selectedPreset == null || selectedPreset.getDnsServers().isEmpty()) {
            Toast.makeText(this, "Please create a custom preset in the Presets tab first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isRootMode) {
            if (isRootActive) {
                RootDnsEngine.restoreRootDnsSettings();
                isRootActive = false;
                Toast.makeText(this, "Root DNS settings restored", Toast.LENGTH_SHORT).show();
            } else {
                if (!RootDnsEngine.isRootAvailable()) {
                    Toast.makeText(this, "Root access not available on this device", Toast.LENGTH_LONG).show();
                    return;
                }
                boolean success = RootDnsEngine.applyRootDnsSettings(selectedPreset.getDnsServers());
                if (success) {
                    isRootActive = true;
                    Toast.makeText(this, "Root DNS applied system-wide", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Failed to apply root DNS settings", Toast.LENGTH_SHORT).show();
                }
            }
            updateUiState();
        } else {
            if (TurboVpnService.isServiceRunning()) {
                Intent intent = new Intent(this, TurboVpnService.class);
                intent.setAction(TurboVpnService.ACTION_STOP);
                startService(intent);
                updateUiState();
            } else {
                Intent prepareIntent = VpnService.prepare(this);
                if (prepareIntent != null) {
                    startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION);
                } else {
                    startVpnService();
                }
            }
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, TurboVpnService.class);
        intent.setAction(TurboVpnService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateUiState();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_VPN_PERMISSION && resultCode == RESULT_OK) {
            startVpnService();
        } else {
            Toast.makeText(this, "VPN Permission rejected", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUiState() {
        boolean active = TurboVpnService.isServiceRunning() || isRootActive;
        btnToggle.setText(active ? "Stop Service" : "Start Service");
        tvStatus.setText("Status: " + (active ? (isRootActive ? "Running (Root Direct)" : "Running (VPN Parallel Racing)") : "Stopped"));
    }

    private void updateLiveStats() {
        ParallelDnsResolver resolver = TurboVpnService.getActiveResolver();
        if (resolver == null) {
            tvStats.setText("No active parallel DNS resolution session.");
            return;
        }

        DnsServerStats stats = resolver.getStats();
        Map<String, DnsServerStats.Stat> map = stats.getAllStats();

        if (map.isEmpty()) {
            tvStats.setText("Waiting for DNS queries...");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-18s %-9s %-9s %-9s\n", "SERVER IP", "PING", "AVG", "JITTER"));
        sb.append("--------------------------------------------------\n");

        for (DnsServerStats.Stat stat : map.values()) {
            sb.append(String.format("%-18s %-9s %-9s %-9s\n",
                    stat.serverIp,
                    stat.lastRttMs + " ms",
                    String.format("%.1f ms", stat.avgRttMs),
                    String.format("%.1f ms", stat.jitterMs)));
        }

        tvStats.setText(sb.toString());
    }
}
