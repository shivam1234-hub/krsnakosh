# Tag Ledger: setup

## 1. Install the Android app

1. Copy `TagLedger.apk` to your phone. Any route works: USB, Google Drive, or emailing it to yourself.
2. Open the file on the phone. Android asks you to allow installs from that app (Files, Drive or Gmail). Allow it, then tap **Install**.
   - Play Protect may warn that it doesn't recognise the app. That's expected for an app that isn't from the Play Store. Tap **More details → Install anyway**.
3. Open **Tag Ledger**. Go to **Settings → Bank SMS** and turn on **Read bank SMS on this phone**. Allow SMS and notifications when Android asks.
   - Android 13 and later may say the setting is **restricted** for a sideloaded app. If so: open Android **Settings → Apps → Tag Ledger**, tap **⋮ → Allow restricted settings**, then try the switch again.
4. Tap **Find payments from the last 30 days** to pull in recent payments.

From now on:
- **Every UPI debit or credit SMS** becomes a notification and a "payment to review". Check the title and tags, then tap **Add**. After you've tagged a payee once, later payments to them arrive with the same title and tags.
- **Google Pay → open a payment → Share → Tag Ledger.** The app reads the amount, payee, time and UPI reference from the receipt, on the phone. It keeps the screenshot as proof and opens a new entry. Tap **Save** and you're back in Google Pay.
- **Any entry → Attach proof photo** adds a screenshot or bill photo to an older entry.

Payments are matched by UPI reference, so sharing a receipt for a payment that also came in by SMS won't add it twice.

## 2. Connect your Google Sheet and Drive (optional, recommended)

This gives you a live **Expenses** tab in a Google Sheet, and copies proof photos into a **Tag Ledger proofs** folder in your Drive.

1. On a laptop, create a new Google Sheet. Name it `Tag Ledger`.
2. In the sheet, open **Extensions → Apps Script**. Delete the sample code and paste in everything from [`apps-script/Code.gs`](apps-script/Code.gs).
3. On the line `const SECRET = 'CHANGE-ME-…'`, replace the value with a long random string. You can make one with `openssl rand -hex 16`.
4. Click **Save**. Choose `testInbox` in the function menu and click **Run**. Allow access to Sheets and Drive when Google asks.
5. Click **Deploy → New deployment → Web app**. Set **Execute as: Me** and **Who has access: Anyone**. Click **Deploy** and copy the URL that ends in `/exec`.
6. **After any change to Code.gs**, publish it to the same URL: **Deploy → Manage deployments → ✏️ Edit → Version: New version → Deploy**. Saving the file alone doesn't update the live web app.
7. On the phone, open **Tag Ledger → Settings → Google Sheet and Drive**. Paste the URL and the secret, then tap **Save and test**.

After that, every save syncs automatically. **Open Google Sheet** in Settings takes you to the tab.

"Anyone" is required so the phone can reach the script without a Google sign-in. Requests without your secret are refused.

Proof photos are shared as **anyone with the link can view**, so the links in the Sheet open for whoever you share the Sheet with. Receipts show names and UPI IDs, so share the Sheet only with people who should see them.

## 3. Export

- **Share icon on the home screen** opens **Export**. It starts with the filters you have on the home screen, and you can change:
  - **Format:** Excel (.xlsx) or CSV.
  - **Dates:** this month, last month, last 3 months, this year, all time, or any From and To dates.
  - **Type:** all, spent only, or income only.
  - **Tags:** tap a tag once to include it and twice to leave it out. `#untagged` matches entries with no tags. With several tags, choose whether an entry needs any of them or all of them.
  - **Amount:** at least and/or at most.
  - **Text:** words that must appear in the title, note or payee.
  - **More filters:** with or without a proof photo, and the source (typed in, bank SMS, Google Pay share, imported).
  - **Sort:** newest first, oldest first, or biggest amount.
  - **Columns:** choose which columns to include. The app remembers your columns and format for next time.
  - **Summary sheets** (Excel only): a Summary tab with the filters used and totals, plus By tag and By month tabs.

  The count and totals at the bottom update as you change things. Tap **Export** to send the file to Drive, Gmail, Sheets or any other app.
- **Google Sheet:** the Expenses tab always holds everything, including a link to each proof photo.

## Privacy

- SMS are read on the phone. Only the payments you confirm are sent to your Sheet, and only if you connected it.
- Receipt text is read on the phone by Google's offline text recognition. The screenshot is uploaded to your own Drive and shared as view-only to anyone with its link.
- The app has no account and no server of its own.

## Web version (laptop)

`index.html` is the same ledger for a browser. Run `python3 -m http.server 5173` in this folder and open `http://localhost:5173`. Its data stays in that browser and doesn't sync with the phone. Use the phone app plus the Google Sheet as your main record.

## Building the app yourself

```
cd android
./gradlew testDebugUnitTest assembleRelease   # needs Android Studio's JDK and SDK
```

The APK is written to `android/app/build/outputs/apk/release/app-release.apk`. It's signed with the local debug key, which is fine for installing on your own phone. To update the app later, install a new APK over the old one. This only works if it's built on the same Mac, because Android requires the same signing key, and your data is kept.
