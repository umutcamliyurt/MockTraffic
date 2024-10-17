package com.nemesis.mocktraffic;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_POST_NOTIFICATIONS = 1;
    private static final int REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 2;

    private CheckBox trafficCheckBox;
    private TextView trafficStatsTextView;
    private TextView statusTextView;

    private BroadcastReceiver statsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TrafficService.ACTION_UPDATE_STATS.equals(intent.getAction())) {
                int requestCount = intent.getIntExtra("requestCount", 0);
                trafficStatsTextView.setText("Traffic Stats: " + requestCount + " requests");

                // Save request count to SharedPreferences
                SharedPreferences preferences = getSharedPreferences("app_prefs", MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putInt("request_count", requestCount);
                editor.apply();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI elements
        trafficCheckBox = findViewById(R.id.trafficCheckBox);
        trafficStatsTextView = findViewById(R.id.trafficStatsTextView);
        statusTextView = findViewById(R.id.statusTextView);

        // Restore saved traffic generation setting
        SharedPreferences preferences = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean trafficEnabled = preferences.getBoolean("traffic_enabled", false);
        trafficCheckBox.setChecked(trafficEnabled);

        // Restore the saved request count
        int savedRequestCount = preferences.getInt("request_count", 0);
        trafficStatsTextView.setText("Traffic Stats: " + savedRequestCount + " requests");

        // Check and request POST_NOTIFICATIONS permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            }
        }

        // Toggle traffic generation when checkbox is clicked
        trafficCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Save the traffic generation setting
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("traffic_enabled", isChecked);
            editor.apply();

            if (isChecked) {
                // Check if POST_NOTIFICATIONS permission is granted (Android 13+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Notification permission required to enable traffic generation.", Toast.LENGTH_LONG).show();
                        trafficCheckBox.setChecked(false);
                        return;
                    }
                }

                // Request to ignore battery optimizations
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Android 6.0+
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        Intent intentExempt = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intentExempt.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intentExempt, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        return; // Wait for user to respond
                    }
                }

                // Start the TrafficService
                Intent serviceIntent = new Intent(this, TrafficService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                Toast.makeText(this, "Traffic Generation Enabled", Toast.LENGTH_SHORT).show();
                statusTextView.setText("Traffic Generation Enabled");
            } else {
                // Stop the TrafficService
                Intent serviceIntent = new Intent(this, TrafficService.class);
                stopService(serviceIntent);
                Toast.makeText(this, "Traffic Generation Disabled", Toast.LENGTH_SHORT).show();
                statusTextView.setText("Traffic Generation Disabled");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register the receiver
        IntentFilter filter = new IntentFilter(TrafficService.ACTION_UPDATE_STATS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {  // Android 13 and above
            registerReceiver(statsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statsReceiver, filter); // Older versions
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Unregister the receiver to prevent leaks
        unregisterReceiver(statsReceiver);
    }

    // Handle the result of permission requests
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted; no action needed
                Toast.makeText(this, "Notification permission granted.", Toast.LENGTH_SHORT).show();
            } else {
                // Permission denied
                Toast.makeText(this, "Notification permission denied. Cannot enable traffic generation.", Toast.LENGTH_LONG).show();
                trafficCheckBox.setChecked(false);
            }
        }
    }

    // Handle the result of battery optimization exemption request
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                // User granted exemption
                Toast.makeText(this, "Battery optimization exemption granted.", Toast.LENGTH_SHORT).show();

                // Start the TrafficService
                Intent serviceIntent = new Intent(this, TrafficService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                statusTextView.setText("Traffic Generation Enabled");
            } else {
                // User denied exemption
                Toast.makeText(this, "Battery optimization exemption denied. Traffic generation may be limited.", Toast.LENGTH_LONG).show();
                trafficCheckBox.setChecked(false);

                // Optionally, show a dialog explaining why the exemption is needed
                new AlertDialog.Builder(this)
                        .setTitle("Battery Optimization")
                        .setMessage("To ensure reliable traffic generation, please allow the app to ignore battery optimizations in settings.")
                        .setPositiveButton("Open Settings", (dialog, which) -> {
                            Intent intentExempt = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                            startActivity(intentExempt);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }
    }
}
