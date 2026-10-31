package in.srcjdcoem.payhook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "SRCPayHook";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Device boot completed: initializing PayHook service & flushing outbox...");
            try {
                Intent sIntent = new Intent(context, PaymentNotificationListenerService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(sIntent);
                } else {
                    context.startService(sIntent);
                }
            } catch (Exception e) {
                Log.w(TAG, "Boot start service notice: " + e.getMessage());
            }

            // Immediately trigger outbox sync for any payments queued before shutdown
            try {
                HttpPostWorker.triggerOutboxFlush();
            } catch (Exception ignored) {}
        }
    }
}
