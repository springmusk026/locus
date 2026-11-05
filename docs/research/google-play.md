# Publishing Locus on Google Play

Can Locus ship on Google Play, and what would a Play build cost? Researched on 2026-11-05. All web sources were read that day unless a note says otherwise. Most Play Help pages show no "last updated" date, only "©2026 Google", so each citation gives the read date. Each section separates **Policy says** (quoted or closely paraphrased from Google) from **Inference** (our reading, which Google hasn't confirmed). Nothing here has been submitted, changed in code or done in Play Console.

## Verdict

Play is feasible. The largest risk is the Linux workspace, and it can't be removed in advance; it can only be accepted or avoided. Play's policy forbids downloading executable code from outside Play. Google offers no exception for a user-installed Linux userland. Even so, several apps doing exactly that are live on Play today and update regularly:

- Termux (10M+, packages via `apt`)
- UserLAnd (1M+, proot plus downloaded distros)
- Andronix (1M+)
- RikkaHub (10K+, a proot "Workspace" that downloads an Ubuntu rootfs)
- AndroidHarness (an agent app with an in-app Linux environment)

That is precedent, not a rule. Enforcement is discretionary, and a reviewer can reject or remove Locus under the same text. The recommended route is a `play` flavour that changes four things:

- The update checker and `REQUEST_INSTALL_PACKAGES` are removed.
- An in-app "Report reply" action is added. Play's AI-Generated Content policy requires one for chatbot apps.
- Linux and local MCP servers sit behind an explicit Play-specific consent.
- The build ships as an AAB signed by a Google-held key, not the release key.

A build-time switch to remove Linux, local MCP and the terminal should be kept ready as the fallback if review rejects them. Without them Locus still works through cloud models, the on-device model, the browser, SSH and remote MCP, but it loses its most distinctive feature. Engineering is about 1–1.5 weeks; the calendar time is at least 3–4 weeks, because a new personal account needs a 14-day closed test with 12 testers.

## 1. The executable-code rule

### Policy says

From the Device and Network Abuse policy ([answer 9888379](https://support.google.com/googleplay/android-developer/answer/9888379), read 2026-11-05):

> "An app distributed via Google Play may not modify, replace, or update itself using any method other than Google Play's update mechanism. Likewise, an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play. This restriction does not apply to code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs (such as JavaScript in a webview or browser)."

> "Apps or third-party code, like SDKs, with interpreted languages (JavaScript, Python, Lua, etc.) loaded at run time (for example, not packaged with the app) must not allow potential violations of Google Play policies."

Two of the policy's common-violation examples matter here:

- "Apps or third party code (for example, SDKs) that download executable code, such as dex files or native code, from a source other than Google Play."
- "Apps or third party code (for example, SDKs) containing a webview with added JavaScript Interface that loads untrusted web content…"

Two other examples bear on the store listing and on how users treat the shell: "Apps that facilitate or provide instructions on how to hack services, software or hardware, or circumvent security protections" and "Apps that access or use a service or API in a manner that violates its terms of service".

The platform rule is separate from the Play rule. For apps targeting Android 10 (API 29) and higher, "Execution of files from the writable app home directory is a W^X violation. Apps should load only the binary code that's embedded within an app's APK file. Untrusted apps that target Android 10 cannot invoke `execve()` directly on files within the app's home directory" ([Android 10 behaviour changes](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)).

### How Locus maps onto it

| Locus feature | What runs | Covered by the rule? |
|---|---|---|
| proot, its loader, llama.cpp and the terminal's JNI | `.so` files inside the APK (`core/linux/src/main/jniLibs`, `core/ondevice`) | No. They come from Play. |
| GGUF and LiteRT model weights | Data read by bundled code | No. Weights aren't code (inference, but uncontroversial). |
| Mermaid, charts and artifacts | JavaScript in a WebView | No. Named as the exception. |
| Agent browser | Web pages in a WebView, driven with `evaluateJavascript`; **no `addJavascriptInterface`** (`grep` 2026-11-05; ARCHITECTURE.md) | No. It also avoids the JavaScript-interface example. |
| Alpine minirootfs, `apk add` packages | Native ELF binaries downloaded from dl-cdn.alpinelinux.org, which proot's loader maps with `mmap(PROT_EXEC)` (ARCHITECTURE.md §"Why it's allowed") | **Yes, by the letter.** This is "native code from a source other than Google Play". |
| Python and Node scripts in Linux | Interpreted, but the interpreters themselves are downloaded native binaries | Same as above. |
| Local MCP servers via `npx` and `uvx` | npm and PyPI packages, sometimes with native wheels or addons | Same as above. |

**Inference.** The VM/interpreter exception doesn't clearly cover Locus. proot is not a VM: guest binaries run natively on the CPU. They do reach Android APIs only indirectly: they have no binder (SECURITY.md), so there are no Android framework calls, only Linux syscalls. That indirect access is the only argument for the exception. The honest reading is that the Linux feature violates the policy as written, and that Google currently doesn't enforce it against user-initiated Linux environments (§3).

## 2. Termux's history (W^X and targetSdk 29)

- In 2019 Termux learned that "the ability to run execve() on files within an application's home directory will be removed in target API > 28" ([termux-app#1072](https://github.com/termux/termux-app/issues/1072), opened 2019-03-18).
- Termux stayed on targetSdk 28. Termux's own account: "Termux was not able to be updated on Google Play starting in 2020, and new devices were not allowed to install Termux from Google Play" ([termux-play-store profile](https://github.com/termux-play-store/.github/blob/main/profile/README.md), read 2026-11-05). So it was the **target API requirement** that pushed Termux off Play, not a removal for downloaded code.
- On 2024-06-07 Termux came back to Play from a separate codebase ([termux-play-store](https://github.com/termux-play-store)). The changes were:
  - the latest targetSdk, with binaries started through `/system/bin/linker` (the `termux-exec` work-around);
  - no `sharedUserId`, which "did not pass the Google Play review";
  - "less permissions, as the initial Google Play review did not approve the usage of multiple permissions".
- Termux on Play still installs packages with `apt` from Termux's own repositories, and the profile's changelog runs to 2026-02-11.
- The Play listing (`com.termux`, fetched 2026-11-05) shows **10M+ downloads, updated Oct 10, 2026**. Its description says: "At first start a small base system is installed - desired packages can then be installed using the apt package manager."
- Termux's main README calls the Play build "experimental", with "extensive adjustments in order to pass policy requirements". It records that Play tried to update F-Droid installs and failed, because the keys differ ([termux-app README](https://github.com/termux/termux-app#google-play-store-experimental-branch)).

**Lesson for Locus.** Locus already uses the W^X-compatible route: proot ships in the APK as `libproot.so`, and the guest binaries are mapped by the loader. That technical problem is solved. The policy question is the same one Termux faces, and Termux has been on Play for 2.5 years with downloaded packages.

## 3. Comparable apps on Play

Listings were fetched from `play.google.com/store/apps/details?id=…` on 2026-11-05. Source repositories were read the same day.

| App (package) | On Play? | Installs, last update | How Linux works | Play build vs others |
|---|---|---|---|---|
| **Termux** (`com.termux`) | Yes | 10M+, Oct 10 2026 | Bundled bootstrap, then `apt` packages from Termux repos, run through `/system/bin/linker`. proot works ("0.126 … fixes `proot` usage"). | Separate codebase ([termux-play-store](https://github.com/termux-play-store)), different signing key, Android 11+, fewer permissions, no `sharedUserId`. |
| **UserLAnd** (`tech.ula`) | Yes | 1M+, Nov 5 2026 | proot, busybox and others are shipped as "fake .so" files in jniLibs with `useLegacyPackaging true`. Distros download from GitHub releases (`DEFAULT_FILESYSTEM_URL` in [app/build.gradle](https://github.com/CypherpunkArmory/UserLAnd/blob/master/app/build.gradle)). targetSdk 37. | The same repo has white-label flavours. One of them, **AiCLI** (`tech.ula.aicli`, 10+, Jun 20 2026), is on Play and runs Claude Code, Codex CLI and Gemini CLI inside Ubuntu. |
| **Andronix** (`studio.com.techriz.andronix`) | Yes | 1M+, Nov 4 2026, in-app purchases | The site says "Open Andronix, pick a distro and start the install", "Sandboxed (PRoot)". Termux is only a fallback ([andronix.app](https://andronix.app/)). Where the distro comes from isn't stated. | Closed source; Play is its only listed channel. |
| **AnLinux** (`exa.lnx.a`) | Yes | 1M+, Sep 17 2026, contains ads | Generates a script that you run in Termux: "The bash script download image over internet, then decompress the image and then mount it using PRoot" ([README](https://github.com/EXALAB/AnLinux-App)). It doesn't run Linux itself. | GitHub APK as well. |
| **RikkaHub** (`me.rerere.rikkahub`) | Yes | 10K+, Oct 26 2026 | proot Workspace; see §4. | A `play` flavour that only hides update checks. |
| **AndroidHarness** (`com.androidharness.app`) | Yes | 1K+, Nov 5 2026 | Agent app. Bundled Bash runs "through the system dynamic linker from Android 10 up", and an optional in-app Linux environment, Shizuku, wireless ADB and root are offered ([README](https://github.com/Sanuu7/AndroidHarness)). It asks for All files access. | Play and GitHub. |
| **ZeroTermux** | Not found on Play | — | A Termux fork, `targetSdkVersion=28` in [gradle.properties](https://github.com/hanxinhao000/ZeroTermux/blob/master/gradle.properties). It **can't** meet Play's target API rule. | GitHub and its own links only. |
| **Operit** (`com.ai.assistance.operit`) | Not found on Play | — | Agent app with Ubuntu 24.04 under proot ([README](https://github.com/AAswordman/Operit)). | GitHub. |
| **OpenMinis** | Not on Play (App Store for iOS; GitHub APK for Android) | — | Alpine under PRoot, rootfs built into the app ([README](https://github.com/OpenMinis/OpenMinis)). | — |

**Enforcement history.** No primary or secondary source was found for a Play removal of a proot or Linux-environment app under the executable-code rule. Termux's exit was the target API level. Magisk Manager (2017) was removed under security and executable-code policies ([Android Authority](https://www.androidauthority.com/magisk-manager-removed-google-play-store-777134/), secondary). Absence of evidence is weak, because removals are rarely public.

## 4. RikkaHub, checked

**Is it on Play?** Yes:

- `me.rerere.rikkahub` is listed as "RikkaHub", 10K+ downloads, updated Oct 26, 2026.
- Its "What's new" begins "新增 SSH 远程工作区" ("Added SSH remote workspace").
- The Data safety summary says "No data shared with third parties".
- The repo README links both "Download from Website (Recommended)" and Google Play ([README](https://github.com/rikkahub/rikkahub)).

**The Linux feature.** The README lists "📦 Workspace: a proot-based Linux agent environment". It was added in [#1269](https://github.com/rikkahub/rikkahub/pull/1269) (2026-06-14). The code lives in the `workspace/` module:

- `workspace/src/main/jniLibs/{arm64-v8a,x86_64}/libproot_exec.so` and `libproot_loader.so`: proot in the APK, the same pattern as Locus.
- `ProotShellRunner.kt` runs proot from `nativeLibraryDir` with `PROOT_LOADER` set.
- `RootfsInstaller.kt` downloads a tar.gz from a URL and unpacks it. In `WorkspaceDetailPage.kt` the URL is an **editable text field** ("tar.gz download URL") with the default `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz`.
- The UI text says: "Will download tar.gz and extract to the linux directory of %s" and "Runs in a Linux sandbox on this device and needs a Rootfs".
- The agent runs shell commands through `WorkspaceTools.kt`. MCP and Skills are supported.
- There is also an SSH workspace backend.

**Play build vs GitHub build.** `app/build.gradle.kts` has a `distribution` dimension with two flavours. `github` is the default and builds an APK; `play` builds an AAB with `bundlePlayRelease`. Both use the same applicationId. The only switch is `BuildConfig.IS_PLAY_STORE`:

> "Play 版不能自更新，相关入口通过 BuildConfig.IS_PLAY_STORE 关闭" (the Play version can't self-update; those entry points are switched off with `IS_PLAY_STORE`).

Code search (2026-11-05) finds it in only three places: `ChatVM.kt` (the update check), `ChatDrawer.kt` (the update card) and `SettingPreferencesNotificationPage.kt` (the update notification setting). **So the Play build of RikkaHub keeps the proot Workspace and its rootfs download unchanged.** The Play listing's SSH-workspace changelog matches the GitHub releases 3.1.0 and 3.1.1 (2026-10-19 and 10-24).

**Inference:** the Play build was reviewed and published with the Workspace in it. That is the strongest Play precedent for Locus.

**Things RikkaHub doesn't do that Locus would need to consider:**

- No in-app report action was found: `strings.xml` has no report or flag string other than "Crash Report". So RikkaHub appears to ship without the AI-content report action. That suggests enforcement is uneven, not that the requirement is gone (§5).
- It declares `ACCESS_LOCAL_NETWORK`, `PACKAGE_USAGE_STATS`, `READ_CALENDAR`/`WRITE_CALENDAR`, `RECORD_AUDIO`, `CAMERA` and FGS `specialUse` and `dataSync`. That's a broader permission set than Locus, and it was accepted.
- The signing keys aren't public. Whether the Play and website builds share a key is unknown.

## 5. AI app policies

### Policy says

- **Scope.** The [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/14094294) covers "Text-to-text AI chatbot apps, in which the AI generated chatbot interaction is a central feature of the app". Out of scope are apps that only host AI content, summarise-only apps, and "Productivity apps that use AI to improve an existing feature". Violative examples include "Content generated to facilitate bullying and harassment" and "Malicious code creation". Developers must prevent "Restricted Content".
- **In-app reporting.** The policy page itself no longer mentions reporting, but the companion Help article [AI-Generated Content (answer 13985936)](https://support.google.com/googleplay/android-developer/answer/13985936) says apps "must contain in-app user reporting or flagging features that allow users to report or flag offensive content to developers", and "Developers should utilize user reports to inform content filtering and moderation in their apps". Press coverage of the October 2023 announcement adds "without needing to exit the app" ([9to5Google](https://9to5google.com/2023/10/25/google-play-gen-ai-policy/), secondary).
- **Bring-your-own key and model choice.** Neither page mentions API keys, user-chosen providers or agents.
- **Third-party AI and user data.** The [July 15, 2026 announcement](https://support.google.com/googleplay/android-developer/answer/17134731) clarifies that "User Data requirements also cover third-party AI integrations", and developers remain responsible for limited use, disclosure and consent.
- **Agents.** The [October 29, 2026 announcement](https://support.google.com/googleplay/android-developer/answer/18100766) adds AppFunctions rules (effective 2027-04-14 per [Policy Deadlines](https://support.google.com/googleplay/android-developer/table/12921780)): AppFunctions are "strictly prohibited from deceiving or manipulating AI agents", bypassing security controls or interfering with other apps. It also adds Payments guidance for "digital purchases through AI or agentic experiences". No Play policy found governs an agent app that acts on the device itself.
- **Accessibility.** AccessibilityService needs "a clear Play Console declaration and demo video" ([Permissions policy](https://support.google.com/googleplay/android-developer/answer/9888170)). **Locus doesn't use it** (README, Privacy), so this doesn't apply.

### Inference

- Locus's chat is central, so the policy covers it. A report action is required for the Play build whatever RikkaHub does.
- Locus has no server, so a report must reach the developer some other way. Options:
  - **(a)** An in-app sheet that posts to a minimal developer-run endpoint. This is the most clearly compliant. It also adds a network destination and a Data safety entry (user-initiated, content the user chose to send).
  - **(b)** A prefilled email or GitHub issue. Simpler, but it leaves the app, which the 2023 wording disallows.

  Recommended: (a), with a local "hide this reply" as well. The user previews exactly what's sent: the reply, optionally the prompt, the model name and the reason. Keys and other chats are never included.
- Bring-your-own key itself isn't restricted. The [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/9888379) example "Apps that access or use a service or API in a manner that violates its terms of service" is a real risk for the **ChatGPT Plus/Pro sign-in** (Codex OAuth used from a third-party app; see docs/research/claude-subscription.md for the provider-terms side). Consider leaving that provider out of the Play build, or confirming OpenAI's terms allow it, before submitting.
- Play reviewers need to reach the core feature. Under App content → App access, give instructions: the on-device Gemma download, or a spend-capped test key. Third-party summaries of the App access page say credentials must work from anywhere and stay valid; Google's page wasn't opened directly.

## 6. Other Play policies that apply

Locus's permissions come from the main-branch manifests, read with grep on 2026-11-05.

| Topic | Policy says (source, read 2026-11-05) | Locus today | Play build action |
|---|---|---|---|
| Self-update | "may not modify, replace, or update itself using any method other than Google Play's update mechanism" ([9888379](https://support.google.com/googleplay/android-developer/answer/9888379)) | Opt-in GitHub updater; `-Plocus.updateCheck=false` turns it off; Play is already in `StoreInstallers` | Build with `locus.updateCheck=false` (docs/design/updates.md §Play). |
| `REQUEST_INSTALL_PACKAGES` | Only for "Web browsing or search", "Communication services that support attachments", "File sharing, transfer or management", "Enterprise device management", "Backup & restore", "Device Migration / Phone Transfer"; enforced since July 11, 2022 ([12085295](https://support.google.com/googleplay/android-developer/answer/12085295)) | Declared in `core/data` | **Remove** in a play manifest with `tools:node="remove"`. Locus's use (self-update) isn't permitted. |
| `QUERY_ALL_PACKAGES` | Needs a declaration ([9888170](https://support.google.com/googleplay/android-developer/answer/9888170)) | Not declared; only a `<queries>` for `RecognitionService` and one in core/data | Nothing to do. |
| `SYSTEM_ALERT_WINDOW`, background location, `MANAGE_EXTERNAL_STORAGE`, contacts permissions, Accessibility | Restricted or declaration-gated (same page; contacts from 2027-01-27) | None declared. Contacts and photos use system pickers. | Nothing to do. |
| `READ_CALENDAR`, `RECORD_AUDIO` | Dangerous permissions: request at run time with an explanation; not in the restricted list (same page) | Declared; asked in context | Nothing beyond the Data safety form. |
| Foreground service types | Apps targeting 14+ declare each type in App content with a description, the impact if deferred, and a **video**. "All foreground service types are subject to review." `dataSync` must be user-initiated in the video. ([13392821](https://support.google.com/googleplay/android-developer/answer/13392821)) | `specialUse` (agent task; Telegram remote, both with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) and `dataSync` (model download, Linux setup) | Owner records three short videos: start a task, turn on Telegram remote, start a model download or Linux install. |
| Local network permission | New: "Apps targeting Android 17 or higher (API 37+) must submit a Play Console declaration" ([Oct 29 2026 announcement](https://support.google.com/googleplay/android-developer/answer/18100766)); deadline **2027-08-25** ([deadlines](https://support.google.com/googleplay/android-developer/table/12921780)) | `ACCESS_LOCAL_NETWORK` declared, targetSdk 37; used for user-added LAN providers (Ollama, LM Studio), SSH and MCP | Fill in the declaration when it opens. Justification: self-hosted model servers on the LAN, which no picker can choose. |
| Default assistant role | The Permissions policy only ties "Assistant handler" to the SMS and Call Log exceptions ([9888170](https://support.google.com/googleplay/android-developer/answer/9888170)); no rule for `ROLE_ASSISTANT` itself | `ACTION_ASSIST` activity only; no VoiceInteractionService; no SMS or call log | Nothing to do (inference). |
| Exported automation intents | No specific policy | `RUN_TASK` dangerous permission; other callers confirmed | Nothing to do. |
| Data safety form | "Even developers with apps that do not collect any user data must complete this form"; "'Collect' means transmitting data from your app off a user's device"; exemptions for on-device processing, user-initiated transfers and end-to-end encryption ([10787469](https://support.google.com/googleplay/android-developer/answer/10787469)) | No developer collection. Chats, files and voice transcripts go to the provider the user configured. | Conservative answer (inference): declare "User content" (messages and other content, files, photos sent as attachments) as **collected**, for **App functionality**, not shared (user-initiated to the user's own provider), encrypted in transit, deletable in the app; reports, if built, as user-initiated collection. Declare nothing for analytics or crash data. "No data collected" would be easy to dispute. |
| Privacy policy | A link "in the designated field within Play Console" **and** "link or text within the app itself", covering contact details, data types, sharing, security, retention and deletion ([10144311](https://support.google.com/googleplay/android-developer/answer/10144311)) | README "Privacy" section; no hosted policy URL | Publish `PRIVACY.md` (or a page on the project site) and link it from About & privacy. |
| Prominent disclosure | Needed when access "may fall outside what users would reasonably expect"; must be in the app, describe the data, and come before an affirmative consent (same page) | Linux install shows a third-party notice | Make the Linux consent explicit (§9). |
| Target API | From Aug 31 2026: "New apps and app updates must target Android 16 (API level 36) or higher" ([target-sdk](https://developer.android.com/google/play/requirements/target-sdk)) | targetSdk 37 (`KotlinAndroid.kt`) | Already met. |
| 16 KB pages | Apps targeting 35+ "must support 16 KB memory page sizes"; from **Feb 1, 2027** non-compliant updates are blocked ([page-sizes](https://developer.android.com/guide/practices/page-sizes)) | proot built with 16 KB alignment (FDROID.md); llama.cpp with NDK r29; `useLegacyPackaging = true` | Check the AAB's APKs with `check_elf_alignment.sh`. Should pass. |
| App Bundle | New apps must publish as AAB since Aug 2021 ([Android Developers Blog, June 2021](https://android-developers.googleblog.com/2021/06/the-future-of-android-app-bundles-is.html)) | APK per ABI via `splits.abi` | Add `bundlePlayRelease`. Play generates ABI splits from the bundle. **Test on the phone** that proot is still extracted to `nativeLibraryDir` from a bundle install (UserLAnd's build notes say extraction must stay on). |
| Other console items | Content rating questionnaire ("Google Play does not allow unrated apps", July 2026 announcement); target audience; trader status ("Starting November 2026", Oct 29 announcement); App access | — | Owner: set audience to 18+ to stay out of Families; answer the rating questionnaire honestly (AI chat, unrestricted web access). |

## 7. Signing

### Policy says ([Play App Signing, 9842756](https://support.google.com/googleplay/android-developer/answer/9842756))

- New apps default to a Google-generated key: "your app is automatically enrolled in quantum-ready, hybrid signing with Google-generated keys".
- Other options are "Use the same key as another app in this developer account" and "Provide a copy of your app signing key" (encrypted with PEPK).
- The upload key stays with you; "Google uses [the app signing key] to sign the final APKs delivered to users' devices"; "For maximum security, your upload key and app signing key should be different."
- For other stores, either download Google-signed universal APKs, or "Generate the key yourself, using the app signing key you want to use for all app stores, and transfer a copy to Google."
- An annual "key upgrade for all installs on Android 17 (API level 37) or higher" is available.
- [Code transparency](https://developer.android.com/guide/app-bundle/code-transparency) is optional. A developer-only key signs a list of DEX and native-library hashes, so users can check that Google didn't change the code. "The Android OS does not verify code transparency files at install time."

### Can Locus upload its release key?

Yes. Choose "Provide a copy of your app signing key" with the release key (SHA-256 `0fc4ab07…0e1b`). GitHub, F-Droid (reproducible, developer-signed) and Play installs would then all carry one signature, so Android lets any source update any other.

| | Upload the release key | Let Google generate the Play key |
|---|---|---|
| Switching source without uninstalling | Works | Doesn't work; needs backup, uninstall, restore (Locus has encrypted backup; the updater already explains "Signed with another key") |
| Who can sign `dev.locus` for **every** channel | You **and Google**. A Google-signed APK would install over F-Droid and GitHub installs. | Only you, for GitHub and F-Droid. Google signs only Play installs. |
| F-Droid reproducible builds | Unchanged: F-Droid still compares its build with your GitHub APK. But the signature no longer proves the APK came from you alone. | Unchanged, and the signature keeps its meaning. |
| Play trying to "update" a GitHub or F-Droid install with the reduced Play build | Succeeds silently if the Play versionCode is higher, swapping the full build for the Play one | Fails (as Termux saw), so a versionCode plan is still needed |
| Key loss or compromise at Google | Exposes every channel | Exposes Play only |
| Developer verification | One key covers all channels | Play auto-registers its key ("If you use Play App Signing … your eligible apps will be part of the automatic registration process"), and the console allows "multiple signing keys for a single package" ([FAQ](https://developer.android.com/developer-verification/guides/faq)) |

**Recommendation (inference): let Google generate the Play key, and keep the release key off Google's servers.** Reasons:

- The Play flavour differs from the others: no updater, maybe no Linux. A silent cross-channel "update" is a bug, not a feature.
- The release key is Locus's root of trust for GitHub, F-Droid and its provenance attestations.
- Add code transparency to the AAB, so the Play build's code can be checked against the source.

Use a separate **upload key** held in the `release` environment, next to the release key but different from it.

Give the AAB a versionCode below that release's GitHub codes, for example `versionCode * 10 + 1` (GitHub uses `+3` and `+4`). Then Play never looks newer than the same GitHub or F-Droid release. This is the same fix Termux applied to its F-Droid version codes (2024-06-22 in the termux-play-store changelog).

## 8. New personal developer account

### Policy says

- "Developers with personal accounts created after November 13, 2023, must run a closed test" with "a minimum of 12 testers who have been opted in continuously for at least 14 days" before production ([14151465](https://support.google.com/googleplay/android-developer/answer/14151465), read 2026-11-05; still current).
- Identity: "To publish apps on Google Play, you must verify these identity details". Personal accounts give a legal name, address, email and phone, verified by one-time code, plus trader status. Organisations also need a D-U-N-S number: "You can't create an organization developer account without a valid D-U-N-S number"; it's free and "can take up to 30 days" ([13628312](https://support.google.com/googleplay/android-developer/answer/13628312)). The verification FAQ says up to 28 days.
- Developer verification: "developers with a Play Console account can use it as the single place to manage all their verification requirements, including for their apps distributed outside of Play" ([FAQ](https://developer.android.com/developer-verification/guides/faq)). Play apps had to be registered in Play Console by 2026-09-30 ([deadlines](https://support.google.com/googleplay/android-developer/table/12921780)).

### Inference

- When the user's account was created decides whether the closed test applies. If it was created after 2023-11-13 as a personal account, plan for 12 opted-in testers for 14 continuous days, then the production-access application.
- Do the `dev.locus` developer verification (DISTRIBUTION.md) **in this Play Console account** rather than a separate Android Developer Console account. Register `dev.locus` with the release key; Play App Signing adds the Play key automatically.
- Risk: verification then depends on the Play account's standing. Termination removes "all apps in your catalog" and permanently suspends related accounts ([enforcement, 9899234](https://support.google.com/googleplay/android-developer/answer/9899234)). Whether a terminated Play account loses its package registrations for outside-Play distribution isn't documented. Treat it as a real tail risk for the GitHub and F-Droid channels.

## 9. What the Play flavour changes

| Area | GitHub / F-Droid | Play | Why |
|---|---|---|---|
| Update checker | Opt-in | Compiled out (`UPDATE_CHECK_ENABLED=false`) | Self-update ban |
| `REQUEST_INSTALL_PACKAGES` | Declared | Removed in `src/play/AndroidManifest.xml` | Not a permitted use |
| Report a reply | — | Long-press or overflow on an assistant reply → **Report**: reason, optional note, preview of what's sent, send, then sent, failed with retry, or offline queued. Optionally hide the reply locally. | AI-Generated Content |
| Linux install | Third-party notice, Install | The same notice plus a Play consent sheet: what is downloaded (Alpine and packages from alpinelinux.org; npm and PyPI for local MCP), that Google Play and Locus haven't reviewed it, that it runs with Locus's rights (SECURITY.md wording), and an explicit **I understand, install** | Prominent-disclosure pattern; frames Linux as the user's own choice |
| Local MCP templates (`npx`/`uvx`) | Offered | Offered once Linux is installed; the template sheet repeats "downloads and runs code from npm/PyPI" | Same as above |
| ChatGPT-plan sign-in | Offered | Off or hidden until OpenAI's terms are confirmed | ToS bullet in Device and Network Abuse |
| Privacy policy | README | Link in About & privacy and in Play Console | User Data policy |
| Fallback (`locus.linux=false`), only if review rejects Linux | — | No Linux card, terminal, `run_command` or local MCP; tools and onboarding hide them; Workspace still has files, SSH and remote MCP; About says "This version doesn't include the Linux workspace" (no off-Play download link; whether Play allows one wasn't checked) | Keeps the account in good standing |
| Gemini Nano (ROADMAP P7) | — | Possible later, Play only | Not required |

**Effort.** These estimates assume the house rule: wired end to end, every state, design-system components only.

| Work | Size |
|---|---|
| `distribution` flavours (`github` default and `play`); manifest removal; Play versionCode; `bundlePlayRelease`; AAB signing with the upload key in the signing job; 16 KB and bundle install check on the phone | S–M, 1–2 days |
| Report action: UI and all states, the endpoint, rate limiting, privacy text, Data safety update | M, 2–4 days |
| Play Linux and MCP consent sheet | S, about 1 day |
| Privacy policy page and in-app link | S, half a day |
| Hiding the ChatGPT plan in Play | S, half a day |
| Fallback: Linux compiled out, everything that depends on it hidden | M, 2–3 days (only if needed) |
| **Total** | **about 1–1.5 weeks**, plus 2–3 days if the fallback is needed |

The owner's time in Play Console comes on top: about a day for forms and videos. The calendar is at least 14 days of closed testing plus review, which often takes several days for a new app.

## 10. Release plan

**Code (us):**

1. Add the `play` flavour, manifest overlay, versionCode rule and `bundlePlayRelease`. Add the AAB to `release.yml`, signed with the upload key in the signing job, which runs no project code. Optionally add code transparency.
2. Build the report action and its endpoint, the Play Linux and MCP consent, the privacy policy page and its in-app link, and the ChatGPT-plan gate.
3. Install the AAB with `bundletool build-apks --connected-device` or Play internal app sharing. Check that Linux install, proot exec, llama.cpp, 16 KB alignment, the installer detection ("Installed from Google Play") and the hidden update UI all work. Follow the device-install guard.
4. Prepare the fallback flag in a branch, unmerged until it's needed.

**Play Console (owner):**

1. Check the account's creation date and type, which decides the closed-test rule. Finish identity verification and trader status.
2. Create the app: package `dev.locus`, free, category Productivity or Tools, target audience 18+.
3. Play App Signing: **Google-generated key**. Register the upload key certificate.
4. App content:
   - privacy policy URL;
   - Data safety (as in §6);
   - content rating;
   - App access instructions (on-device model, or a capped test key);
   - foreground service declarations with three videos;
   - ads: none;
   - government app: no;
   - later, the local network declaration (by 2027-08-25).
5. Developer verification tab: confirm `dev.locus` is registered and add the release-key fingerprint for outside-Play builds (DISTRIBUTION.md steps 2–4).
6. Store listing: plain description. Name providers only to identify compatibility. Present Linux as a developer workspace, never as hacking tooling. Use the screenshots from `docs/screenshots`.
7. Internal testing, then a **closed test with at least 12 testers for 14 continuous days**, then apply for production access, then a staged production rollout.
8. Update DISTRIBUTION.md: Google Play becomes "Live".

## 11. Risks

- **Rejection or removal over downloaded Linux code (medium).** The policy text is against Locus. The precedents (Termux, UserLAnd, Andronix, RikkaHub, AndroidHarness) are live, but Google gives no exception. If it happens, ship the fallback; don't argue several rounds.
- **Account suspension (low to medium, high impact).** Suspensions are strikes. "Multiple suspensions or suspensions for egregious violations" lead to termination, which removes all apps and permanently suspends related accounts ([9899234](https://support.google.com/googleplay/android-developer/answer/9899234)). To limit it:
  - on a first rejection, fix and resubmit rather than appeal repeatedly;
  - keep the fallback ready;
  - never re-enable a cut feature through a remote switch, because the deceptive-behaviour policy covers manipulating review.
- **Developer verification coupled to Play (unknown).** If `dev.locus` is registered through this Play account and the account is terminated, what happens to GitHub and F-Droid installs isn't documented.
- **Reports and Locus's "no server" claim.** A report endpoint is the first Locus-run service. It must take only what the user sends, keep no IP logs, and appear in Data safety, the privacy policy and README Privacy.
- **ChatGPT-plan sign-in.** The terms-of-service bullet in Device and Network Abuse.
- **Agent misuse.** "Malicious code creation" is a violative example for AI apps. Locus relies on the provider's safety layer plus approvals. The report action and the listing's framing matter here.
- **Bundle installs.** proot must still be extracted to `nativeLibraryDir`. Test on the phone before the closed test.
- **Two flavours to maintain.** Each Play-only gate is another test path. Keep the differences to the table in §9.

## Not verified

- Andronix's distro download source, and whether it targets a current SDK; it's closed source.
- Whether RikkaHub's Play and website builds share a signing key.
- Google's own App access page; only third-party summaries were read.
- Whether Play allows an in-app pointer to the off-Play full build.
- The date the user's Play Console account was created.
- Whether the F-Droid client claims update ownership (Android 14+), which would also stop Play from replacing F-Droid installs.
