package in.srcjdcoem.payhook;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
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

    // SharedPreferences Keys for App Filtering
    public static final String PREFS_NAME = "src_payhook_prefs";
    public static final String KEY_PAYTM_BIZ = "pref_paytm_biz";
    public static final String KEY_PAYTM_PERSONAL = "pref_paytm_personal";
    public static final String KEY_PHONEPE = "pref_phonepe";
    public static final String KEY_GPAY = "pref_gpay";
    public static final String KEY_BANK_SMS = "pref_bank_sms";
    public static final String KEY_BHIM = "pref_bhim";

    // Application context holder for sensory feedback and background workers
    public static volatile Context appContext = null;

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

        // 1. Paytm for Business
        if (allowPaytmBiz && (pkg.contains("com.paytm.business") || (pkg.contains("paytm") && pkg.contains("merchant")))) {
            return true;
        }

        // 2. Paytm Personal
        if (allowPaytmPersonal && (pkg.contains("net.one97.paytm") || (pkg.contains("paytm") && !pkg.contains("business")))) {
            return true;
        }

        // 3. PhonePe (Personal & Merchant)
        if (allowPhonePe && pkg.contains("phonepe")) {
            return true;
        }

        // 4. Google Pay (GPay)
        if (allowGPay && (pkg.contains("paisa") || (pkg.contains("google") && pkg.contains("pay")))) {
            return true;
        }

        // 5. Bank SMS & Messages
        if (allowBankSms && (pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms") || pkg.contains("message"))) {
            return true;
        }

        // 6. BHIM & NPCI UPI Apps
        if (allowBhim && (pkg.contains("npci") || pkg.contains("bhim") || pkg.contains("upi"))) {
            return true;
        }

        return false;
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

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        appContext = getApplicationContext();
        addLog("Service connected and listening for payments.");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        addLog("Service disconnected.");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;

        try {
            String packageName = sbn.getPackageName();
            if (packageName == null) return;

            // 1. Check user app filter: Is this package allowed?
            if (!isPackageAllowed(this, packageName)) {
                // Silently ignore non-payment apps (Meesho, WhatsApp, Amazon, YouTube, etc.)
                return;
            }

            Bundle extras = sbn.getNotification().extras;
            if (extras == null) return;

            CharSequence titleSeq = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence textSeq = extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence bigTextSeq = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

            String title = titleSeq != null ? titleSeq.toString() : "";
            String text = textSeq != null ? textSeq.toString() : "";
            String bigText = bigTextSeq != null ? bigTextSeq.toString() : "";

            String combinedText = (title + " " + text + " " + (bigText.isEmpty() ? "" : bigText)).trim();
            if (combinedText.isEmpty()) return;

            // 2. Check genuine payment criteria: Exclude shopping promotions and ensure real payment receipt terms
            if (!isGenuinePaymentReceipt(combinedText)) {
                return;
            }

            // 3. Local deduplication: Drop identical notifications fired within 30s (Android banner updates / re-posts)
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

            addLog("Payment notification intercepted from " + packageName + ": " + (combinedText.length() > 60 ? combinedText.substring(0, 60) + "..." : combinedText));
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
