package in.srcjdcoem.payhook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class PaymentNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "SRCPayHook";
    public static final String WEBHOOK_URL = "https://www.srcjdcoem.in/api/upi/webhook?secret=SRC_UPI_2026_GATEWAY";
    public static final String SECRET_KEY = "SRC_UPI_2026_GATEWAY";
    public static final String FOREGROUND_CHANNEL_ID = "src_payhook_listener";
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;

    // SharedPreferences Keys for App Filtering & Persistent Outbox Queue
    public static final String PREFS_NAME = "src_payhook_prefs";
    public static final String PREF_OUTBOX_KEY = "src_payhook_outbox_queue_v1";
    public static final String KEY_PAYTM_BIZ = "pref_paytm_biz";
    public static final String KEY_PAYTM_PERSONAL = "pref_paytm_personal";
    public static final String KEY_PHONEPE = "pref_phonepe";
    public static final String KEY_GPAY = "pref_gpay";
    public static final String KEY_BANK_SMS = "pref_bank_sms";
    public static final String KEY_BHIM = "pref_bhim";

    // Application context and connection status
    public static volatile Context appContext = null;
    public static volatile boolean isServiceConnected = false;
    public static volatile PaymentNotificationListenerService instance = null;

    // In-memory deduplication cache: drops identical notification updates within 20 seconds
    private static final Map<String, Long> RECENT_NOTIFICATIONS = new LinkedHashMap<String, Long>();
    private static final long DEDUP_WINDOW_MS = 20000; // 20 seconds

    // Circular in-memory log buffer for MainActivity UI
    public static final List<String> LOG_BUFFER = new ArrayList<>();
    public static MainActivity activeMainActivity = null;

    // Periodic Background Status-Bar Tray Poller (every 30s)
    private Handler trayScanHandler;
    private TrayScanTask trayScanTask;

    private static class TrayScanTask implements Runnable {
        private final PaymentNotificationListenerService service;

        TrayScanTask(PaymentNotificationListenerService service) {
            this.service = service;
        }

        @Override
        public void run() {
            if (service != null) {
                try {
                    service.scanActiveTrayNotifications();
                    HttpPostWorker.triggerOutboxFlush();
                } catch (Exception e) {
                    Log.w(TAG, "Tray scanner cycle notice: " + e.getMessage());
                } finally {
                    if (service.trayScanHandler != null) {
                        service.trayScanHandler.postDelayed(this, 30000);
                    }
                }
            }
        }
    }

    public static synchronized void addLog(String entry) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + timestamp + "] " + entry;
        Log.i(TAG, line);
        LOG_BUFFER.add(0, line);
        if (LOG_BUFFER.size() > 50) {
            LOG_BUFFER.remove(LOG_BUFFER.size() - 1);
        }
        if (activeMainActivity != null) {
            try {
                activeMainActivity.runOnUiThread(activeMainActivity);
            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // PERSISTENT OUTBOX QUEUE (ZERO DATA LOSS INVARIANT)
    // =========================================================================

    public static synchronized void enqueueNotification(Context ctx, String content, String packageName, long postTime) {
        if (ctx == null) ctx = appContext;
        if (ctx == null) return;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String existingJson = prefs.getString(PREF_OUTBOX_KEY, "[]");
            JSONArray array = new JSONArray(existingJson);

            // Avoid enqueuing duplicate items with identical text within the queue
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (content.equals(obj.optString("text")) && packageName.equals(obj.optString("packageName"))) {
                    return; // Already present in pending outbox
                }
            }

            JSONObject item = new JSONObject();
            item.put("id", UUID.randomUUID().toString());
            item.put("text", content);
            item.put("packageName", packageName);
            item.put("postTime", postTime > 0 ? postTime : System.currentTimeMillis());
            item.put("enqueuedAt", System.currentTimeMillis());
            item.put("attempts", 0);

            array.put(item);
            prefs.edit().putString(PREF_OUTBOX_KEY, array.toString()).apply();

            int pending = array.length();
            String preview = content.length() > 50 ? content.substring(0, 50) + "..." : content;
            addLog("QUEUED (" + pending + " in outbox): " + preview);

            // Trigger immediate background sync
            HttpPostWorker.triggerOutboxFlush();
        } catch (Exception e) {
            Log.e(TAG, "Error enqueuing notification to persistent outbox", e);
        }
    }

    public static synchronized JSONArray getPendingOutbox(Context ctx) {
        if (ctx == null) ctx = appContext;
        if (ctx == null) return new JSONArray();
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String existingJson = prefs.getString(PREF_OUTBOX_KEY, "[]");
            return new JSONArray(existingJson);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static synchronized void removeOutboxItem(Context ctx, String id) {
        if (ctx == null) ctx = appContext;
        if (ctx == null) return;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String existingJson = prefs.getString(PREF_OUTBOX_KEY, "[]");
            JSONArray array = new JSONArray(existingJson);
            JSONArray updated = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (!id.equals(obj.optString("id"))) {
                    updated.put(obj);
                }
            }
            prefs.edit().putString(PREF_OUTBOX_KEY, updated.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error removing outbox item", e);
        }
    }

    public static synchronized int getOutboxCount(Context ctx) {
        return getPendingOutbox(ctx).length();
    }

    // =========================================================================
    // ROBUST PACKAGE AND PAYMENT RECEIPT FILTERING
    // =========================================================================

    public static boolean isPackageAllowed(Context ctx, String packageName) {
        if (packageName == null) return false;
        String pkg = packageName.toLowerCase(Locale.ROOT);

        // Always allow our own package for test pings & diagnostics
        if (ctx != null && pkg.equals(ctx.getPackageName().toLowerCase(Locale.ROOT))) {
            return true;
        }

        SharedPreferences prefs = ctx != null 
            ? ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) 
            : null;

        boolean allowPaytmBiz = prefs == null || prefs.getBoolean(KEY_PAYTM_BIZ, true);
        boolean allowPaytmPersonal = prefs == null || prefs.getBoolean(KEY_PAYTM_PERSONAL, true);
        boolean allowPhonePe = prefs == null || prefs.getBoolean(KEY_PHONEPE, true);
        boolean allowGPay = prefs == null || prefs.getBoolean(KEY_GPAY, true);
        boolean allowBankSms = prefs == null || prefs.getBoolean(KEY_BANK_SMS, true);
        boolean allowBhim = prefs == null || prefs.getBoolean(KEY_BHIM, true);

        // 1. Paytm (Business & Personal)
        if ((allowPaytmBiz || allowPaytmPersonal) && pkg.contains("paytm")) {
            return true;
        }

        // 2. PhonePe (Personal & Merchant)
        if (allowPhonePe && pkg.contains("phonepe")) {
            return true;
        }

        // 3. Google Pay (GPay)
        if (allowGPay && (pkg.contains("paisa") || (pkg.contains("google") && pkg.contains("pay")))) {
            return true;
        }

        // 4. Bank SMS, Messaging & Truecaller Alerts
        if (allowBankSms && (pkg.contains("messaging") || 
                             pkg.contains("mms") || 
                             pkg.contains("sms") || 
                             pkg.contains("message") || 
                             pkg.contains("truecaller"))) {
            return true;
        }

        // 5. BHIM & NPCI UPI Apps
        if (allowBhim && (pkg.contains("npci") || pkg.contains("bhim") || pkg.contains("upi"))) {
            return true;
        }

        // 6. ALL Indian Mobile Banking Apps (Canara, SBI Yono, HDFC, ICICI, Axis, PNB, BoB, etc.)
        if (pkg.contains("canara") || 
            pkg.contains("canarabank") || 
            pkg.contains("sbi") || 
            pkg.contains("yono") || 
            pkg.contains("hdfc") || 
            pkg.contains("icici") || 
            pkg.contains("axis") || 
            pkg.contains("kotak") || 
            pkg.contains("baroda") || 
            pkg.contains("pnb") || 
            pkg.contains("unionbank") || 
            pkg.contains("centralbank") || 
            pkg.contains("indusind") || 
            pkg.contains("federalbank") || 
            pkg.contains("idbi") || 
            pkg.contains("bank") || 
            pkg.contains("mobilebanking")) {
            return true;
        }

        return false;
    }

    public static String extractAllNotificationText(Notification notification) {
        if (notification == null) return "";
        StringBuilder sb = new StringBuilder();

        if (notification.tickerText != null) {
            sb.append(notification.tickerText).append(" ");
        }

        Bundle extras = notification.extras;
        if (extras != null) {
            String[] keys = new String[]{
                Notification.EXTRA_TITLE,
                Notification.EXTRA_TITLE_BIG,
                Notification.EXTRA_TEXT,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_INFO_TEXT,
                Notification.EXTRA_SUMMARY_TEXT
            };

            for (String key : keys) {
                try {
                    CharSequence cs = extras.getCharSequence(key);
                    if (cs != null && cs.length() > 0) {
                        sb.append(cs).append(" ");
                    }
                } catch (Exception ignored) {}
            }

            try {
                CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null) {
                    for (CharSequence line : lines) {
                        if (line != null && line.length() > 0) {
                            sb.append(line).append(" ");
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return sb.toString().trim();
    }

    public static boolean isGenuinePaymentReceipt(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);

        // Strict promotional advertisement filters (only exclude unambiguous e-commerce shopping campaigns)
        // Do NOT exclude "cashback" or "offer" unconditionally because Paytm pushes include "Scratchcard offer"
        if (lower.contains("sale starting @") || 
            lower.contains("sale starting at") || 
            lower.contains("flat 50% off") || 
            lower.contains("flat 70% off") || 
            lower.contains("shop now") || 
            lower.contains("order food now") ||
            lower.contains("limited time deal on products") ||
            lower.contains("use coupon code to buy")) {
            return false;
        }

        // Comprehensive Indian Banking & UPI Payment Receipt Keywords
        boolean hasReceiptKeyword = lower.contains("received") ||
                                    lower.contains("credited") ||
                                    lower.contains("paid you") ||
                                    lower.contains("payment of") ||
                                    lower.contains("payment received") ||
                                    lower.contains("payment from") ||
                                    lower.contains("credit of") ||
                                    lower.contains("credit by") ||
                                    lower.contains("credit:") ||
                                    lower.contains("credit :") ||
                                    lower.contains("cr to a/c") ||
                                    lower.contains("acct credited") ||
                                    lower.contains("a/c credited") ||
                                    lower.contains("has sent you") ||
                                    lower.contains("transferred") ||
                                    lower.contains("deposited") ||
                                    lower.contains("utr") ||
                                    lower.contains("ref no") ||
                                    lower.contains("ref:") ||
                                    lower.contains("txn id") ||
                                    lower.contains("test ping");

        boolean hasAmountOrCurrency = lower.contains("rs") ||
                                      lower.contains("₹") ||
                                      lower.contains("inr");

        return hasReceiptKeyword && (hasAmountOrCurrency || lower.contains("test ping") || lower.contains("utr"));
    }

    public static void triggerSuccessFeedback() {
        try {
            new Thread(() -> {
                try {
                    ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
                    toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 250);
                    try { Thread.sleep(260); } catch (Exception ignored) {}
                    toneGen.release();
                } catch (Exception e) {
                    Log.w(TAG, "Audio tone failed: " + e.getMessage());
                }
            }).start();

            Context ctx = appContext != null ? appContext : activeMainActivity;
            if (ctx != null) {
                Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createWaveform(new long[]{0, 90, 60, 110}, -1));
                    } else {
                        v.vibrate(new long[]{0, 90, 60, 110}, -1);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Feedback trigger error: " + e.getMessage());
        }
    }

    public static void tryForceRebind(Context context) {
        if (context == null) return;
        ComponentName componentName = new ComponentName(context, PaymentNotificationListenerService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                NotificationListenerService.requestRebind(componentName);
                Log.d(TAG, "NotificationListenerService.requestRebind() requested.");
            } catch (Exception e) {
                Log.w(TAG, "requestRebind failed: " + e.getMessage());
            }
        }

        try {
            PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            Log.d(TAG, "Component enabled state toggled for rebind.");
        } catch (Exception e) {
            Log.w(TAG, "Component toggle failed: " + e.getMessage());
        }
    }

    private void startForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    FOREGROUND_CHANNEL_ID,
                    "SRC PayHook Live Listener",
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Maintains background UPI payment listening for festival desks");
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(channel);
                }
            }

            Intent notifIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notifIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
            );

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, FOREGROUND_CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            Notification notification = builder
                .setContentTitle("SRC PayHook Active (v1.5)")
                .setContentText("Monitoring Paytm, PhonePe, GPay, SMS & Banking in real time")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.w(TAG, "Foreground notification start notice: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        instance = this;
        startForegroundNotification();

        trayScanHandler = new Handler(Looper.getMainLooper());
        trayScanTask = new TrayScanTask(this);
        trayScanHandler.post(trayScanTask);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (trayScanHandler != null && trayScanTask != null) {
            trayScanHandler.removeCallbacks(trayScanTask);
            trayScanHandler = null;
            trayScanTask = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();
        HttpPostWorker.triggerOutboxFlush();
        return START_STICKY;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        appContext = getApplicationContext();
        isServiceConnected = true;
        instance = this;
        startForegroundNotification();
        addLog("Listener connected & monitoring payments in real time (v1.5).");

        scanActiveTrayNotifications();
        HttpPostWorker.triggerOutboxFlush();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        isServiceConnected = false;
        instance = null;
        addLog("WARNING: System disconnected listener. Requesting rebind...");
        tryForceRebind(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        processNotification(sbn, false);
    }

    public void scanActiveTrayNotifications() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null && active.length > 0) {
                for (StatusBarNotification sbn : active) {
                    if (sbn != null) {
                        processNotification(sbn, true);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Tray backlog scan note: " + e.getMessage());
        }
    }

    private void processNotification(StatusBarNotification sbn, boolean isBacklogScan) {
        if (sbn == null || sbn.getNotification() == null) return;

        try {
            String packageName = sbn.getPackageName();
            if (packageName == null) return;

            // 1. Check user app filter: Is this package allowed?
            if (!isPackageAllowed(this, packageName)) {
                return;
            }

            // 2. Extract comprehensive text from all possible notification fields
            String combinedText = extractAllNotificationText(sbn.getNotification());
            if (combinedText.isEmpty()) return;

            // 3. Verify genuine payment criteria
            if (!isGenuinePaymentReceipt(combinedText)) {
                return;
            }

            // 4. Local deduplication: Drop identical notifications fired within 20s (Android banner updates / re-posts)
            String dedupKey = packageName + "::" + combinedText.replaceAll("\\s+", " ").trim();
            long now = System.currentTimeMillis();
            synchronized (RECENT_NOTIFICATIONS) {
                Long lastSeen = RECENT_NOTIFICATIONS.get(dedupKey);
                if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) {
                    return;
                }
                if (RECENT_NOTIFICATIONS.size() > 100) {
                    RECENT_NOTIFICATIONS.clear();
                }
                RECENT_NOTIFICATIONS.put(dedupKey, now);
            }

            // 5. Enqueue to atomic persistent disk outbox
            long postTime = sbn.getPostTime() > 0 ? sbn.getPostTime() : now;
            enqueueNotification(this, combinedText, packageName, postTime);

        } catch (Exception e) {
            Log.e(TAG, "Error processing notification", e);
        }
    }

    public static void forwardNotificationAsync(final String notificationContent, final String packageName) {
        enqueueNotification(appContext, notificationContent, packageName, System.currentTimeMillis());
    }
}
