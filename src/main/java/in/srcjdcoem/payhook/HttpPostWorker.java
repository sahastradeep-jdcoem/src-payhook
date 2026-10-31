package in.srcjdcoem.payhook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HttpPostWorker implements Runnable {

    private static volatile boolean isFlushing = false;
    private static final Object FLUSH_LOCK = new Object();

    public static void triggerOutboxFlush() {
        synchronized (FLUSH_LOCK) {
            if (isFlushing) return;
            isFlushing = true;
        }
        new Thread(new HttpPostWorker()).start();
    }

    @Override
    public void run() {
        try {
            android.content.Context ctx = PaymentNotificationListenerService.appContext;
            if (ctx == null) return;

            JSONArray outbox = PaymentNotificationListenerService.getPendingOutbox(ctx);
            if (outbox.length() == 0) return;

            for (int i = 0; i < outbox.length(); i++) {
                JSONObject item = outbox.getJSONObject(i);
                String id = item.optString("id");
                String text = item.optString("text");
                String pkg = item.optString("packageName");
                long postTime = item.optLong("postTime", System.currentTimeMillis());
                int attempts = item.optInt("attempts", 0);

                boolean success = attemptPost(text, pkg, postTime, attempts + 1);
                if (success) {
                    PaymentNotificationListenerService.removeOutboxItem(ctx, id);
                    PaymentNotificationListenerService.addLog("FORWARDED OK (HTTP 200): Synced to website!");
                    PaymentNotificationListenerService.triggerSuccessFeedback();
                } else {
                    // Update attempts count on this item in outbox
                    item.put("attempts", attempts + 1);
                    int remaining = outbox.length() - i;
                    PaymentNotificationListenerService.addLog("OUTBOX PAUSED: Network timeout/dip. " + remaining + " item(s) retained on disk.");
                    break; // Stop iteration; wait for next retry cycle (30s) or connectivity restore
                }
            }
        } catch (Exception e) {
            PaymentNotificationListenerService.addLog("Worker cycle error: " + e.getMessage());
        } finally {
            synchronized (FLUSH_LOCK) {
                isFlushing = false;
            }
        }
    }

    private boolean attemptPost(String notificationContent, String packageName, long postTime, int attemptNumber) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(PaymentNotificationListenerService.WEBHOOK_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000); // 12 seconds connection timeout
            conn.setReadTimeout(12000);    // 12 seconds read timeout
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("x-src-webhook-key", PaymentNotificationListenerService.SECRET_KEY);
            conn.setRequestProperty("User-Agent", "SRCPayHook-NativeAndroid/1.5");

            JSONObject payload = new JSONObject();
            payload.put("text", notificationContent);
            payload.put("notificationText", notificationContent);
            payload.put("packageName", packageName);
            payload.put("attempt", attemptNumber);
            payload.put("postTime", postTime);
            payload.put("notificationTimestamp", postTime);
            payload.put("timestamp", System.currentTimeMillis());

            byte[] postBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(postBytes);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                return true;
            } else {
                BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream()
                ));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                PaymentNotificationListenerService.addLog("SERVER HTTP " + responseCode + ": " + sb.toString());
                return false;
            }
        } catch (Exception err) {
            PaymentNotificationListenerService.addLog("NETWORK DIP (" + err.getMessage() + ") — will retry next cycle");
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
