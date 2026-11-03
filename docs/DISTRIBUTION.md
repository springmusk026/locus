# Distribution

Where Locus is published, and what only the owner can do. Updated 2026-11-03.

| Channel | State | Details |
|---|---|---|
| GitHub releases | Live. One signed APK per ABI per release. | [RELEASING.md](RELEASING.md) |
| F-Droid | Ready to submit from the next release (0.1.6): reproducible, recipe drafted. Not submitted. | [FDROID.md](FDROID.md) |
| IzzyOnDroid | Request text ready. Likely declined under its generative-AI policy; owner's call. | [FDROID.md](FDROID.md#izzyondroid) |
| Google Play | Not planned (a reduced Play flavour is on the roadmap as "only if wanted"). | [ROADMAP.md](ROADMAP.md) |
| Android developer verification | Not registered. Needed before the 2027 global rollout. | Below |

## Android developer verification

Sources, read 2026-11-03: [Android developer verification](https://developer.android.com/developer-verification), its [guides overview](https://developer.android.com/developer-verification/guides), [FAQ](https://developer.android.com/developer-verification/guides/faq), [full distribution account](https://developer.android.com/developer-verification/guides/full-distribution), [Android Developer Console guide](https://developer.android.com/developer-verification/guides/android-developer-console), [open-source app registration](https://developer.android.com/developer-verification/guides/open-source-app-registration), and the March 2026 [Android Developers Blog post](https://android-developers.googleblog.com/2026/03/android-developer-verification.html).

### What it is

"Android requires all apps to be registered by verified developers for users to install them on certified Android devices" ([guides](https://developer.android.com/developer-verification/guides)). A developer proves their identity to Google, then registers each package name with its signing key. It's enforced by Google Play services on "all certified Android devices running Android 7 or higher" ([FAQ](https://developer.android.com/developer-verification/guides/faq)), whatever the install source: "If your app runs on a certified device, these requirements apply to you, regardless of your app's download source."

### Timeline

- **August 2026**: "Developer APIs, limited distribution accounts, and power user advanced flow launch" ([overview](https://developer.android.com/developer-verification)).
- **30 September 2026**: enforcement for users in Brazil, Indonesia, Singapore and Thailand, only for installs from participating stores: Google Play, HONOR App Market, OPPO App Market, Galaxy Store, Palm Store, V-Appstore and GetApps ([guides](https://developer.android.com/developer-verification/guides)). "If you distribute your app through other stores, or if users sideload your app directly, these new verification requirements won't apply to your app yet" ([FAQ](https://developer.android.com/developer-verification/guides/faq), updated July 2026).
- **2027**: "Verification capability will be expanded globally for all apps on certified Android devices"; "The verification capability will soon be expanded to all third-party Android app stores."

### What it means for Locus

- **Today**: nothing changes. Locus is installed from GitHub (sideloaded) and, later, F-Droid/IzzyOnDroid, none of which are in the September 2026 list.
- **From the 2027 rollout, if Locus isn't registered**: users can only install or update it after enabling the **advanced flow**: turn on developer options, confirm no one is coaching them, restart, wait "a one-time, one-day wait", confirm with biometrics or PIN, then choose 7 days or indefinitely; each install still warns "unverified developer" with **Install anyway** ([FAQ](https://developer.android.com/developer-verification/guides/faq)). Worse for existing users: "Unregistered apps can only be installed or updated when the advanced flow is enabled or by using ADB", so **in-place updates of an unregistered Locus would fail** for anyone without the advanced flow, including updates through F-Droid clients and Locus's own update check.
- **ADB is exempt**: "you are free to install apps without verification with ADB", so development and `adb install` testing are unaffected.
- **If Locus is registered**: "No changes for your users" ([open-source guide](https://developer.android.com/developer-verification/guides/open-source-app-registration)).
- **One key covers every channel.** F-Droid will publish Locus's own signed APK (reproducible builds, [FDROID.md](FDROID.md#reproducible-builds)), and IzzyOnDroid ships the GitHub APK, so registering `dev.locus` with the release key covers GitHub, F-Droid and IzzyOnDroid installs: "If the open-source repository uses your signing key to distribute the app, you don't need to complete any further steps." Only if F-Droid ever signs Locus with its own key does that key need adding too (the guide's re-signed flow, below).
- **The account type**: a public app needs a **full distribution** account. The free limited distribution account is for "up to 20 devices" without ID, which doesn't fit.

### What the owner has to do

1. **Create an Android Developer Console account** (for apps "distributed exclusively outside of Google Play") at <https://android.google.com/developerconsole>, choosing **full distribution**. As an individual: a government-issued photo ID and a proof of address, plus a private email and phone verified by one-time code ([full distribution](https://developer.android.com/developer-verification/guides/full-distribution)); "typically takes about 10 minutes". Registering as an organization additionally needs a D-U-N-S number (free, "can take up to 28 days") and a website verified in Search Console. Fee: **US$25** ("The $25 fee for the Full Distribution account in the ADC", [FAQ](https://developer.android.com/developer-verification/guides/faq)). If you already have a Play Console account, use its Android developer verification tab instead.
2. **Register the package name `dev.locus`** (Package names → Register package name). Not `dev.locus.debug`: debug builds are never distributed.
3. **Add the release key**: SHA-256 certificate fingerprint `0fc4ab07aa5522dfabac92d818fd5cf075ead96da4e2b40a757ac80541490e1b` (the same value as `AllowedAPKSigningKeys` in the F-Droid recipe). Check it with `apksigner verify --print-certs locus-v<version>-arm64-v8a.apk`.
4. **Prove you own the key.** `dev.locus` already has installs, so the console will most likely ask for an APK signed with the release key that carries its challenge: "Copy the provided snippet, go to your app's source tree, create a file named `adi-registration.properties` in the `assets` folder, and paste the snippet in the file" ([open-source guide](https://developer.android.com/developer-verification/guides/open-source-app-registration)), then upload the signed APK. For Locus: commit the snippet as `app/src/main/assets/adi-registration.properties`, cut a normal release (the workflow signs with the release key; a committed asset doesn't affect reproducible builds), and upload `locus-v<version>-arm64-v8a.apk`. Keep the file afterwards; removing it is another release.
5. **Check the status** under Package names (Registered / Not registered / Draft). Android Studio Panda 4+ also shows it when building a signed APK.
6. **Only if F-Droid signs Locus with its own key** (the recipe's fallback without `binary`): download F-Droid's APK, take its SHA-256 fingerprint, and on `dev.locus` click **Add key** (under 50 F-Droid installs) or **Select key**, then follow the guide's upload steps with that APK.

Nothing here has been registered; it needs the owner's identity and payment. Do it before the 2027 rollout starts, and preferably before submitting to F-Droid, so F-Droid users never see the "unverified developer" warning.
