package in.srcjdcoem.payhook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity implements View.OnClickListener, Runnable {

    private TextView tvStatus;
    private TextView tvStatusDesc;
    private LinearLayout statusCard;
    private View statusDot;
    private LinearLayout layoutRestrictedNotice;
    private Button btnOpenAppInfo;
    private Button btnGrantPermission;
    private Button btnBatteryOpt;
    private Button btnTestConnection;
    private TextView tvConnectionResult;
    private Button btnTestPing;
    private Button btnClearLogs;
    private TextView tvLogs;

    // Payment App Filter Controls
    private CheckBox cbPaytmBiz;
    private CheckBox cbPaytmPersonal;
    private CheckBox cbPhonePe;
    private CheckBox cbGPay;
    private CheckBox cbBankSms;
    private CheckBox cbBhim;
    private TextView tvActiveAppsCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (PaymentNotificationListenerService.appContext == null) {
            PaymentNotificationListenerService.appContext = getApplicationContext();
        }

        tvStatus = findViewById(R.id.tvStatus);
        tvStatusDesc = findViewById(R.id.tvStatusDesc);
        statusCard = findViewById(R.id.statusCard);
        statusDot = findViewById(R.id.statusDot);
        layoutRestrictedNotice = findViewById(R.id.layoutRestrictedNotice);
        btnOpenAppInfo = findViewById(R.id.btnOpenAppInfo);
        btnGrantPermission = findViewById(R.id.btnGrantPermission);
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt);
        btnTestConnection = findViewById(R.id.btnTestConnection);
        tvConnectionResult = findViewById(R.id.tvConnectionResult);
        btnTestPing = findViewById(R.id.btnTestPing);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        tvLogs = findViewById(R.id.tvLogs);

        // App Filter Checkboxes
        cbPaytmBiz = findViewById(R.id.cbPaytmBiz);
        cbPaytmPersonal = findViewById(R.id.cbPaytmPersonal);
        cbPhonePe = findViewById(R.id.cbPhonePe);
        cbGPay = findViewById(R.id.cbGPay);
        cbBankSms = findViewById(R.id.cbBankSms);
        cbBhim = findViewById(R.id.cbBhim);
        tvActiveAppsCount = findViewById(R.id.tvActiveAppsCount);

        setupAppFilters();

        btnOpenAppInfo.setOnClickListener(this);
        btnGrantPermission.setOnClickListener(this);
        btnBatteryOpt.setOnClickListener(this);
        btnTestConnection.setOnClickListener(this);
        btnTestPing.setOnClickListener(this);
        btnClearLogs.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btnOpenAppInfo) {
            // Step 1: Open App Info directly so user can tap 3 dots (⋮) -> "Allow restricted settings"
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(MainActivity.this, "Tap ⋮ (top right) → 'Allow restricted settings'", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Could not open App Info: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else if (id == R.id.btnGrantPermission) {
            // Step 2: Open Notification Access settings so user can toggle ON
            try {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Could not open settings: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else if (id == R.id.btnBatteryOpt) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                        startActivity(fallback);
                    } catch (Exception ignored) {}
                }
            }
        } else if (id == R.id.btnTestConnection) {
            runConnectionDiagnostics();
        } else if (id == R.id.btnTestPing) {
            PaymentNotificationListenerService.addLog("Simulating verified test payment notification...");
            PaymentNotificationListenerService.triggerSuccessFeedback();
            PaymentNotificationListenerService.forwardNotificationAsync(
                "SRC TEST: Received Rs. 1.00 from Test Student via UPI. UTR: 999900001234",
                getPackageName()
            );
            Toast.makeText(MainActivity.this, "Chime played! Payment forwarded to website.", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.btnClearLogs) {
            PaymentNotificationListenerService.LOG_BUFFER.clear();
            updateLogsView();
            Toast.makeText(MainActivity.this, "Logs cleared.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Initializes and binds checkboxes for monitored payment apps.
     * Persists user preferences to SharedPreferences.
     */
    private void setupAppFilters() {
        SharedPreferences prefs = getSharedPreferences(
            PaymentNotificationListenerService.PREFS_NAME,
            Context.MODE_PRIVATE
        );

        setupCheckbox(cbPaytmBiz, prefs, PaymentNotificationListenerService.KEY_PAYTM_BIZ, "Paytm Business");
        setupCheckbox(cbPaytmPersonal, prefs, PaymentNotificationListenerService.KEY_PAYTM_PERSONAL, "Paytm Personal");
        setupCheckbox(cbPhonePe, prefs, PaymentNotificationListenerService.KEY_PHONEPE, "PhonePe");
        setupCheckbox(cbGPay, prefs, PaymentNotificationListenerService.KEY_GPAY, "Google Pay");
        setupCheckbox(cbBankSms, prefs, PaymentNotificationListenerService.KEY_BANK_SMS, "Bank SMS");
        setupCheckbox(cbBhim, prefs, PaymentNotificationListenerService.KEY_BHIM, "BHIM UPI");

        updateActiveAppsCount();
    }

    private void setupCheckbox(CheckBox cb, SharedPreferences prefs, String prefKey, String label) {
        if (cb == null) return;
        boolean isEnabled = prefs.getBoolean(prefKey, true);
        cb.setChecked(isEnabled);

        cb.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            prefs.edit().putBoolean(prefKey, isChecked).apply();
            updateActiveAppsCount();
            PaymentNotificationListenerService.addLog(
                "Filter: " + label + " " + (isChecked ? "ENABLED" : "DISABLED")
            );
        });
    }

    private void updateActiveAppsCount() {
        if (tvActiveAppsCount == null) return;

        int count = 0;
        if (cbPaytmBiz != null && cbPaytmBiz.isChecked()) count++;
        if (cbPaytmPersonal != null && cbPaytmPersonal.isChecked()) count++;
        if (cbPhonePe != null && cbPhonePe.isChecked()) count++;
        if (cbGPay != null && cbGPay.isChecked()) count++;
        if (cbBankSms != null && cbBankSms.isChecked()) count++;
        if (cbBhim != null && cbBhim.isChecked()) count++;

        tvActiveAppsCount.setText(count + "/6 Active");
        if (count == 0) {
            tvActiveAppsCount.setTextColor(Color.parseColor("#B91C1C"));
        } else {
            tvActiveAppsCount.setTextColor(Color.parseColor("#166534"));
        }
    }

    /**
     * Test Mode: Run full connection diagnostics against the webhook endpoint.
     */
    private void runConnectionDiagnostics() {
        btnTestConnection.setEnabled(false);
        btnTestConnection.setText("Running diagnostics...");
        tvConnectionResult.setVisibility(View.VISIBLE);
        tvConnectionResult.setText("Connecting to srcjdcoem.in...");

        PaymentNotificationListenerService.addLog("DIAGNOSTICS: Starting connection test...");

        new Thread(() -> {
            StringBuilder result = new StringBuilder();

            // ── Phase 1: GET health check ──
            try {
                long start = System.currentTimeMillis();
                java.net.URL url = new java.net.URL("https://www.srcjdcoem.in/api/upi/webhook?secret=SRC_UPI_2026_GATEWAY");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "SRCPayHook-Diagnostics/1.2");
                conn.setRequestProperty("x-src-webhook-key", PaymentNotificationListenerService.SECRET_KEY);

                int code = conn.getResponseCode();
                long latency = System.currentTimeMillis() - start;
                conn.disconnect();

                result.append("Phase 1 — GET Health Check\n");
                result.append("  Status:  HTTP ").append(code).append(code == 200 ? " OK" : " WARNING").append("\n");
                result.append("  Latency: ").append(latency).append(" ms\n");
                result.append("  SSL:     Verified\n\n");

                PaymentNotificationListenerService.addLog("DIAGNOSTICS Phase 1: HTTP " + code + " (" + latency + "ms)");
            } catch (Exception e) {
                result.append("Phase 1 — GET Health Check\n");
                result.append("  FAILED: ").append(e.getMessage()).append("\n\n");
                PaymentNotificationListenerService.addLog("DIAGNOSTICS Phase 1 FAILED: " + e.getMessage());
            }

            // ── Phase 2: Authenticated POST test ──
            try {
                long start = System.currentTimeMillis();
                java.net.URL url = new java.net.URL(PaymentNotificationListenerService.WEBHOOK_URL);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("x-src-webhook-key", PaymentNotificationListenerService.SECRET_KEY);
                conn.setRequestProperty("User-Agent", "SRCPayHook-Diagnostics/1.2");

                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("text", "SRC DIAGNOSTICS PING — connection test from PayHook app");
                payload.put("packageName", "in.srcjdcoem.payhook.diagnostics");
                payload.put("timestamp", System.currentTimeMillis());

                byte[] body = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.io.OutputStream os = conn.getOutputStream();
                os.write(body);
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                long latency = System.currentTimeMillis() - start;

                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                        code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream()
                    )
                );
                StringBuilder respBody = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) respBody.append(line);
                br.close();
                conn.disconnect();

                result.append("Phase 2 — Authenticated POST\n");
                result.append("  Status:  HTTP ").append(code).append(code == 200 ? " OK" : "").append("\n");
                result.append("  Latency: ").append(latency).append(" ms\n");
                result.append("  Auth:    ").append(code == 200 ? "Verified" : (code == 401 ? "REJECTED" : "Unknown")).append("\n");

                try {
                    org.json.JSONObject resp = new org.json.JSONObject(respBody.toString());
                    if (resp.has("message")) {
                        result.append("  Server:  ").append(resp.getString("message")).append("\n");
                    }
                    if (resp.has("isPing") && resp.getBoolean("isPing")) {
                        result.append("  Result:  Ping acknowledged by server\n");
                    }
                } catch (Exception ignored) {}

                PaymentNotificationListenerService.addLog("DIAGNOSTICS Phase 2: HTTP " + code + " (" + latency + "ms) — Auth " + (code == 200 ? "OK" : "FAIL"));

                result.append("\n");
                if (code == 200) {
                    result.append("RESULT: All systems operational. Ready for live payments.");
                    PaymentNotificationListenerService.triggerSuccessFeedback();
                } else if (code == 401) {
                    result.append("RESULT: Authentication rejected. Check webhook secret key.");
                } else {
                    result.append("RESULT: Server responded with HTTP ").append(code).append(". Check server logs.");
                }
            } catch (Exception e) {
                result.append("Phase 2 — Authenticated POST\n");
                result.append("  FAILED: ").append(e.getMessage()).append("\n\n");
                result.append("RESULT: Cannot reach server. Check internet connection.");
                PaymentNotificationListenerService.addLog("DIAGNOSTICS Phase 2 FAILED: " + e.getMessage());
            }

            final String output = result.toString();
            runOnUiThread(() -> {
                tvConnectionResult.setText(output);
                btnTestConnection.setEnabled(true);
                btnTestConnection.setText("Run Connection Diagnostics");
            });
        }).start();
    }

    @Override
    public void run() {
        updateLogsView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PaymentNotificationListenerService.activeMainActivity = this;
        checkPermissionsAndStatus();
        updateLogsView();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (PaymentNotificationListenerService.activeMainActivity == this) {
            PaymentNotificationListenerService.activeMainActivity = null;
        }
    }

    private void checkPermissionsAndStatus() {
        boolean isNotificationAccessGranted = isNotificationServiceEnabled();

        if (isNotificationAccessGranted) {
            tvStatus.setText("Listener Active");
            tvStatus.setTextColor(Color.parseColor("#166534"));
            tvStatusDesc.setText("Capturing Paytm, GPay, PhonePe and SMS notifications");
            tvStatusDesc.setTextColor(Color.parseColor("#15803D"));
            statusCard.setBackgroundResource(R.drawable.card_status_active);
            statusDot.setBackgroundResource(R.drawable.dot_green);

            // Hide permission buttons once active
            layoutRestrictedNotice.setVisibility(View.GONE);
            btnOpenAppInfo.setVisibility(View.GONE);
            btnGrantPermission.setVisibility(View.GONE);
        } else {
            tvStatus.setText("Notification Access Required");
            tvStatus.setTextColor(Color.parseColor("#991B1B"));
            tvStatusDesc.setText("Complete Step 1 and Step 2 below to activate listener");
            tvStatusDesc.setTextColor(Color.parseColor("#B91C1C"));
            statusCard.setBackgroundResource(R.drawable.card_status_error);
            statusDot.setBackgroundResource(R.drawable.dot_red);

            // If Android 13+ (API 33+), show step 1 to allow restricted settings
            if (Build.VERSION.SDK_INT >= 33) {
                layoutRestrictedNotice.setVisibility(View.VISIBLE);
                btnOpenAppInfo.setVisibility(View.VISIBLE);
                btnGrantPermission.setText("2. Grant Notification Access");
            } else {
                layoutRestrictedNotice.setVisibility(View.GONE);
                btnOpenAppInfo.setVisibility(View.GONE);
                btnGrantPermission.setText("Grant Notification Access");
            }
            btnGrantPermission.setVisibility(View.VISIBLE);
        }

        // Battery optimization status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                btnBatteryOpt.setText("Battery Optimization Disabled");
                btnBatteryOpt.setEnabled(false);
                btnBatteryOpt.setTextColor(Color.parseColor("#166534"));
                btnBatteryOpt.setBackgroundResource(R.drawable.btn_green);
            } else {
                btnBatteryOpt.setText("Disable Battery Optimization");
                btnBatteryOpt.setEnabled(true);
                btnBatteryOpt.setTextColor(Color.parseColor("#17458F"));
                btnBatteryOpt.setBackgroundResource(R.drawable.btn_secondary);
            }
        }
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateLogsView() {
        List<String> logs = PaymentNotificationListenerService.LOG_BUFFER;
        if (logs.isEmpty()) {
            tvLogs.setText("Waiting for notifications...\n\nWebhook: " + PaymentNotificationListenerService.WEBHOOK_URL);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String logLine : logs) {
                sb.append(logLine).append("\n\n");
            }
            tvLogs.setText(sb.toString());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (PaymentNotificationListenerService.activeMainActivity == this) {
            PaymentNotificationListenerService.activeMainActivity = null;
        }
    }
}
