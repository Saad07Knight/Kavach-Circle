# Kavach Circle: Android app

Team Power Rangers · HackRush 2026 · Cybersecurity track

Kavach Circle is a real Android app. You install the same APK on two phones:

- **The person to protect** (for example, Mom). The app watches for the pattern of a digital arrest scam: a long call, a payment app opened during that call, and a bank OTP arriving. When it sees the pattern, it puts a pause screen on top of Paytm, Google Pay, PhonePe and other payment apps, and alerts the family.
- **The guardian** (for example, her son). Alerts open full screen on this phone and call Mom automatically.

The two phones talk over **signed SMS**, so you need no server and no internet. Each pair of phones shares a secret key, exchanged only by scanning a QR code in person and then sealed in Android Keystore. Every message carries an HMAC-SHA256 signature, a timestamp and a one-time nonce, so a message can't be forged or replayed.

---

## 1. Get a download link for the APK (about 10 minutes, free)

This workspace couldn't build the APK itself, so GitHub builds it for you in the cloud.

1. Sign in at **github.com** (create a free account if needed).
2. Click **+ › New repository**. Name it `kavach-circle`, make it **Public**, and click **Create repository**.
3. On the new repo page, click **uploading an existing file**. Unzip this project on your laptop and drag **everything inside the `kavach-android` folder** into the page. Then click **Commit changes**.
   - **Check that the `.github` folder was uploaded.** Mac and Windows hide folders that start with a dot, so they often get skipped. If yours is missing: click **Add file › Create new file**, type `.github/workflows/build-apk.yml` as the name, paste in the contents of that file from the zip, and commit.
4. Open the **Actions** tab. A run called **Build Kavach Circle APK** starts by itself. Wait about 5 minutes for the green tick.
5. Your permanent download link is:

   ```
   https://github.com/YOUR-USERNAME/kavach-circle/releases/latest/download/KavachCircle.apk
   ```

   Open it on both phones. Every new commit rebuilds the app at the same link.

**Using Android Studio instead:** choose File › Open, pick the `kavach-android` folder, let it sync, then use Build › Build APK(s).

---

## 2. Install on both phones

1. Open the download link on each phone and tap **Download**.
2. Open the file. Android asks you to **allow installing apps from this source**. Allow it once.
3. If Play Protect warns that the app is unknown, tap **More details › Install anyway**. This happens because the app isn't on the Play Store yet.

## 3. Set up

**Phone 1: the person to protect**

1. Choose **Protect me**, then enter your name and 10-digit mobile number.
2. Tap every **Allow** button. Each one opens the right Android screen:
   - **SMS**: spot bank OTPs and send signed alerts to the family
   - **Usage access**: see when Paytm, Google Pay or PhonePe opens
   - **Display over other apps**: show the pause screen on top of payment apps
   - **Phone**: call the family in one tap
   - **Tamper alarm** (device admin): the family is warned before the app can be uninstalled
   - **Battery**: keep the app running in the background
3. Tap **Set PINs**: choose a normal PIN and a different **duress PIN**.
4. Tap **Turn on protection**.

**Phone 2: the guardian**

1. Choose **I'm a guardian**, then enter your name and number, and allow the permissions.
2. On Phone 1, tap **Add a guardian (show QR code)**. On Phone 2, tap **Scan their QR code**.
3. Within a few seconds, Phone 2 shows **Watching over …** and Phone 1 lists the guardian.
4. On Phone 1, tap **Send a test alert** to check the link.

> If Android says **"Restricted setting"** while you are granting a permission, go to Settings › Apps › Kavach Circle › ⋮ (top right) › **Allow restricted settings**, then try again. Android does this for apps installed from outside the Play Store.

---

## 4. Demo script for the judges (about 3 minutes)

**Demo mode** is on by default. In demo mode the pause screen appears after a **1-minute** call instead of 30.

1. From a third phone, video-call Phone 1 on WhatsApp. Play the "CBI officer".
2. After 1 minute, the caller says "open Google Pay". When Phone 1 opens Google Pay, **Kavach's pause screen covers it**.
3. On the pause screen, tap **Ask my family to approve this payment**. Phone 2 shows **Payment approval needed**. Tap **Block this payment**, and Phone 1 shows *"Rahul said do not pay"*.
4. Send Phone 1 an SMS such as `Your OTP is 482913 for UPI transfer`. Now all three signals are met, so Phone 2 gets a full-screen **COERCION ALERT** and calls Phone 1 automatically after 15 seconds.
5. **Duress PIN:** on Phone 1, tap **Turn off protection** and enter the duress PIN. Phone 1 looks switched off, while Phone 2 shows **DURESS PIN USED**.
6. **Screen share:** start a screen share from Phone 1 to the caller. Kavach's screens show as black to the caller.
7. **Tamper alarm:** go to Settings › Security › Device admin apps and untick Kavach Circle. Phone 2 gets **Kavach is being removed**.

Turn **Demo mode** off for real use. Then the call threshold is 30 minutes, the pause before "continue without approval" is 2 minutes, and new guardians become active only after 24 hours.

---

## How it works

| Signal | Android source | What is read |
|---|---|---|
| Call running | `AudioManager.getMode()` (`MODE_IN_CALL` / `MODE_IN_COMMUNICATION`) | Only whether a call is on |
| Payment app opened | `UsageStatsManager` foreground events, checked against 14 payment and bank apps | Only the app's package name |
| Bank OTP arrived | `SMS_RECEIVED` | Only whether the SMS looks like an OTP; the text is never stored |

Code map (`app/src/main/java/com/kavachcircle/app/`):

- `Engine.kt`: the rule engine and the payment-app list (add more bank apps here)
- `InterventionActivity.kt`: the pause screen shown over payment apps
- `AlertActivity.kt`: the guardian's full-screen alert, with auto-call
- `Messenger.kt`: the signed SMS protocol (`KVC1|TYPE|nonce|time|payload|hmac`)
- `Crypto.kt`: Keystore HMAC keys, PIN hashing
- `Services.kt`: background service, SMS receiver, boot receiver, tamper alarm
- `MainActivity.kt`: setup, permissions, pairing, PINs

## Known limits

- **The pause screen is a full screen of its own, not a floating overlay.** Many UPI apps hide floating overlays to block overlay attacks, but they can't stop another app's screen from opening on top.
- **The app can't stop a payment inside Google Pay.** It pauses the person, pulls the family in and asks for approval. A hard bank-side hold needs a bank API (Phase 2 of the roadmap).
- **Alerts cost one SMS each** on the sender's plan.
- **It runs on Android 8 and above. iPhone doesn't allow these signals.**
- **The signing key in `app/kavach-demo.keystore` is for the hackathon only.** Make a private key before any public release.
