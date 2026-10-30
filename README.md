# SRC PayHook — Native Android Notification Webhook Listener

**SRC PayHook** is a lightweight, 100% ad-free, open-source Android notification listener app created for the **Student Representative Council (SRC) JDCOEM** web platform ([srcjdcoem.in](https://www.srcjdcoem.in)).

It captures instant payment notifications from **Paytm for Business**, **Paytm**, **PhonePe**, **Google Pay**, and bank SMS alerts, and forwards verified transaction payloads to the SRC website webhook in real time.

---

## ✨ Features

- **0% Commission Direct UPI Support**: Enables direct P2M bank settlement with zero convenience fees or aggregator charges.
- **Zero Ads & Zero Subscription**: Completely replaces third-party automation tools (like MacroDroid or Tasker) that demand subscriptions or video ads.
- **Ultra Lightweight**: Under **25 KB** compiled APK size; uses `< 5 MB` RAM in the background.
- **Reliable Background Execution**: Built with Android's native `NotificationListenerService` and whitelisting for battery optimization.
- **Live Activity Log**: Displays on-screen logs of all captured notifications, HTTP dispatch codes, and test pings.
- **Pre-Authenticated Security**: Dispatches payloads with a dedicated `x-src-webhook-key` header to prevent forged requests.

---

## 📲 Download & Installation

1. Download the pre-built signed APK:
   - **[Download src-payhook-v1.3.apk](https://www.srcjdcoem.in/download/src-payhook-v1.3.apk)** (Latest v1.3 release)
2. Install the APK on the Android device associated with the festival UPI receiver account.
3. Open the app:
   - Tap **"Grant Notification Access"** to toggle ON `SRC PayHook` in Android Settings.
   - Tap **"Disable Battery Optimization"** so Android never puts the app to sleep.
4. Tap **"Send Test Ping to Website"** to verify that the log displays `FORWARDED OK (HTTP 200)`.

---

## 🛠️ Building from Source

The app uses a lightweight, standalone build script (`build.sh`) that directly uses the Android SDK command-line tools without requiring bulky Gradle wrappers.

### Prerequisites
- Java JDK 17+ (or JDK 21/23)
- Android SDK (`platforms/android-34`, `build-tools/34.0.0`)

### Build Command
```bash
chmod +x build.sh
./build.sh
```
The compiled, zipaligned, and signed release APK will be generated with versioning at `./src-payhook-v<VERSION>.apk` (e.g. `src-payhook-v1.3.apk`) and automatically mirrored to the web portal's `public/download/` directory.

---

## 🔒 Security & Privacy

- **Scope**: The app only reads notifications from approved payment gateways (`com.paytm.business`, `net.one97.paytm`, `com.phonepe.app`, `com.google.android.apps.nbu.paisa.user`, and SMS packages) containing financial confirmation keywords.
- **No Personal Data Stored**: Notifications are not saved to persistent storage or sent anywhere other than the designated college webhook.
- **Transport Security**: All webhook requests are transmitted over encrypted HTTPS with API secret headers.

---

## 📄 License
MIT License. Maintained by Student Representative Council (SRC), JDCOEM Nagpur.
