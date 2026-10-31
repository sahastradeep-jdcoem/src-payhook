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
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PaymentNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "SRCPayHook";
    public static final String WEBHOOK_URL = "https://www.srcjdcoem.in/api/upi/webhook?secret=SRC_UPI_2026_GATEWAY";
    public static final String SECRET_KEY = "SRC_UPI_2026_GATEWAY";
    public static final String FOREGROUND_CHANNEL_ID = "src_payhook_listener";
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;

    // SharedPreferences Keys for App Filtering
    public static final String PREFS_NAME = "src_payhook_prefs";
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

    // In-memory LRU deduplication cache: drops identical notification updates within 30 seconds
    private static final Map<String, Long> RECENT_NOTIFICATIONS = new LinkedHashMap<String, Long>(50, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 100;
        }
    };
    private static final long DEDUP_WINDOW_MS = 30000; // 30 seconds

    // Circular in-memory log buffer for MainActivity UI
    public static final List<String> LOG_BUFFER = new ArrayList<>();
    public static MainActivity activeMainActivity = null;

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

    /**
     * Checks if the package is in the user's enabled apps list in SharedPreferences.
     */
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

        return false;
    }

    /**
     * Extracts text comprehensively from all known notification fields.
     */
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

    /**
     * Strictly verifies that the notification is an actual financial credit/receipt alert,
     * and strictly rejects promotional notifications (Meesho, Amazon, shopping offers).
     */
    public static boolean isGenuinePaymentReceipt(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);

        // Explicitly exclude shopping promotions, deals, and coupon ads
        if (lower.contains("sale") || 
            lower.contains("starting @") || 
            lower.contains("starting at") ||
            lower.contains("flat off") || 
            lower.contains("cashback offer") || 
            lower.contains("discount") || 
            lower.contains("coupon") || 
            lower.contains("shop now") || 
            lower.contains("buy now") ||
            lower.contains("order now") ||
            lower.contains("limited time deal") ||
            lower.contains("use code") ||
            lower.contains("exclusive offer") ||
            lower.contains("save up to")) {
            return false;
        }

        // Must contain genuine incoming payment confirmation terms
        boolean hasReceiptKeyword = lower.contains("received") ||
                                    lower.contains("credited") ||
                                    lower.contains("paid you") ||
                                    lower.contains("payment of") ||
                                    lower.contains("payment received") ||
                                    lower.contains("credit of") ||
                                    lower.contains("has sent you") ||
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

    /**
     * Sensory Feedback: Dual chime tone + crisp double vibration buzz
     */
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

    /**
     * Forces Android OS to reconnect/rebind to this NotificationListenerService if disconnected.
     */
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

    /**
     * Starts persistent foreground notification to ensure Samsung / Xiaomi OS never kills service.
     */
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
                .setContentTitle("SRC PayHook Active")
                .setContentText("Monitoring Paytm, PhonePe, GPay & SMS in real time")
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();
        return START_STICKY;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        appContext = getApplicationContext();
        isServiceConnected = true;
        instance = this;
        startForegroundNotification();
        addLog("Listener connected & monitoring payments in real time.");

        // Scan any unforwarded active payment notifications already in the status bar tray
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

            // 3. Verify genuine payment criteria: Exclude shopping promotions and ensure real payment receipt terms
            if (!isGenuinePaymentReceipt(combinedText)) {
                return;
            }

            // 4. Local deduplication: Drop identical notifications fired within 30s (Android banner updates / re-posts)
            String dedupKey = packageName + "::" + combinedText.replaceAll("\\s+", " ").trim();
            long now = System.currentTimeMillis();
            synchronized (RECENT_NOTIFICATIONS) {
                Long lastSeen = RECENT_NOTIFICATIONS.get(dedupKey);
                if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) {
                    Log.d(TAG, "Suppressed duplicate notification re-post (" + (now - lastSeen) + "ms since first post)");
                    return;
                }
                RECENT_NOTIFICATIONS.put(dedupKey, now);
            }

            String preview = combinedText.length() > 65 ? combinedText.substring(0, 65) + "..." : combinedText;
            addLog("Payment notification intercepted from " + packageName + ": " + preview);
            forwardNotificationAsync(combinedText, packageName);

        } catch (Exception e) {
            Log.e(TAG, "Error processing notification", e);
        }
    }

    public static void forwardNotificationAsync(final String notificationContent, final String packageName) {
        Thread thread = new Thread(new HttpPostWorker(notificationContent, packageName));
        thread.start();
    }
}
