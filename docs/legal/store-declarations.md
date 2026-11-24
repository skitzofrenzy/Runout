# Store declarations: maintainer checklist

What to answer in the app-store consoles for Runout as it is today, and what has to change when each planned feature ships. Facts below were checked in the code on 2026-11-24: `composeApp/src/androidMain/AndroidManifest.xml` (no `uses-permission`, `allowBackup="false"`), `iosApp/iosApp/Info.plist` (no usage-description keys), `gradle/libs.versions.toml` (no network, analytics or advertising library), `composeApp/src/*/kotlin/com/runout/app/storage/` (setup preferences only), and `composeApp/src/androidMain/kotlin/com/runout/app/camera/PlatformPhotoPicker.android.kt` (system camera app and system photo picker; temporary file deleted; only picture orientation read).

Store consoles change their wording. Where an answer below is described rather than quoted, check the live label before submitting, and re-check this file at every release.

## 1. Documents needed per channel

| Document | Google Play | Apple App Store | Direct APK |
|---|---|---|---|
| Privacy policy ([privacy-policy.md](privacy-policy.md)) at a stable public web address (not a PDF), linked in the store listing and inside the app | Required for every app | Required for every app (App Store Connect field and in the app) | Not required by a store; ship it anyway |
| Data safety form / App Privacy details | Required | Required | Not applicable |
| Terms of use ([terms-of-use.md](terms-of-use.md)) | Not required by the store; recommended | Optional if Apple's standard licence is used (see section 3) | Recommended |
| Third-party notices ([third-party-notices.md](third-party-notices.md)) with the Apache-2.0 text | Required by the components' licences when the app is distributed | Same | Same |
| Contact route for questions and complaints | Required in the listing | Required (support URL) | Recommended |
| MIT `LICENSE` | Covers the source code only | Same | Same |

## 2. Google Play, today

| Console item | Answer for the app today |
|---|---|
| App content, Privacy policy | Web address of the published privacy policy. Also add a link or text inside the app. |
| Data safety, does the app collect or share any required user data types | **No.** Nothing is sent off the device. Settings kept on the device are not "collected". |
| Data safety, other questions (encryption in transit, deletion) | Not shown or not applicable when nothing is collected. Confirm in the console. |
| Ads | No ads. |
| App access | All functionality is available without logging in or any special access. |
| Target audience and content | Choose age groups that do **not** include children under 13 (for example 13 and over). Do not opt in to the Families programme. |
| Content rating questionnaire | Answer "No" to violence, sexual content, bad language, controlled substances, gambling or simulated gambling, user-generated content, user interaction, location sharing and digital purchases. Runout has none of these. The rating is calculated by the rating authorities and appears in the console. |
| Permissions and sensitive-permission declarations | None declared. The manifest has no `uses-permission`. |
| Financial, health, news and government declarations | Not applicable. |
| Trader status (EU and UK Digital Services Act) | Required for every account; existing accounts by 2027-02-01, new accounts when created. See section 5. |
| New personal developer account | Accounts created after 2023-11-13 must run a closed test with at least 12 testers opted in for 14 days before applying for production access. |

## 3. Apple App Store, today

| App Store Connect item | Answer for the app today |
|---|---|
| App Privacy | **Data Not Collected.** |
| Privacy policy URL | Web address of the published privacy policy. Also add a link inside the app. |
| Support URL | A page that gives an easy way to contact the developer (the issue tracker and the email address). |
| Age rating questionnaire | Answer "None" to every content question, including simulated gambling, contests, medical or treatment information and violence. The rating is calculated by Apple (expected 4+). Re-read the "health or wellness topics" definition before answering; shot advice is not exercise advice, but answer honestly. |
| Licence agreement | Use Apple's standard Licensed Application End User License Agreement. It already contains an "as is" no-warranty clause and a limit of liability, and it covers Apple's own required terms. Link the Terms of Use in the description as the app's own terms. If you ever use a custom agreement instead, Apple requires it to contain the minimum terms in Exhibit B to Schedule 1 of the Apple Developer Program License Agreement, which include your name and address and a telephone number and email address for complaints, and it may not limit your liability beyond what the law permits. |
| Export compliance | Runout contains no cryptography of its own and makes no network connections. Set `ITSAppUsesNonExemptEncryption` to false in `Info.plist` and answer the export questions accordingly. Confirm the current wording in App Store Connect. |
| Privacy manifest (`PrivacyInfo.xcprivacy`) | The iOS storage code uses `NSUserDefaults`. Apple lists it as a required-reason API, so declare it with the reason "only the app itself can read the data". Confirm the category and reason code in Apple's current documentation when the Xcode project is generated. |
| `Info.plist` usage descriptions | None today. Add one only together with the feature that needs it (section 6). |
| Trader status (EU Digital Services Act) | Required before the app is shown in the EU. See section 5. |
| App review notes | "Offline pool and snooker training aid. No accounts, no network access, no in-app purchases." |

## 4. Direct APK

- Ship the privacy policy, terms of use and third-party notices with the release page.
- Android developer verification: an app installed on a certified Android device is expected to come from a verified developer. Enforcement begins on 2026-09-30 in Brazil, Indonesia, Singapore and Thailand and extends worldwide in 2027. Distributing publicly outside Google Play needs an Android Developer Console account with identity verification and registration of the package name and signing key. "Limited distribution" (up to 20 devices, no identity verification) is not suitable for public downloads. An app also published on Google Play is registered through the Play Console.
- Keep signing keys and `keystore.properties` out of the repository.

## 5. Trader declaration

Runout is published by a sole proprietorship, so declare **trader** in Google Play and in App Store Connect. A sole proprietor is the person, not a separate company: the proprietor's own legal name and address are what the stores verify, and the trade name is how the business is known.

**What each store asks for**

| | Google Play | Apple App Store |
|---|---|---|
| Account type | A personal account can declare trader status. An organisation account and a D-U-N-S number are not needed. | Enrol as an **individual**. Apple says a sole proprietor must join as an individual and the legal name appears as the seller. Trade names, DBAs and fictitious business names are not accepted for an organisation account, so the trade name cannot be the seller name. No D-U-N-S number is needed for an individual. |
| What you enter | Developer name (it may differ from your legal name, so "Nexova Digital" can be the developer name), legal name and legal address (taken from the payments profile and verified), contact email, a verified phone number (needed for trader status), trader status. | Legal name as the seller, then the trader information in App Store Connect: address (or P.O. box), phone number, email, payment account details, and a self-certification that the offer complies with EU law. |
| What the public sees | In the EEA and the UK, the trader's name, address, phone number and email on the app's page. | In the 27 EU storefronts, the trader's contact information on the product page. |
| Deadline | New accounts when created; existing accounts by 2027-02-01. | Before the app is shown in the EU. |

**Details you still have to supply:** the proprietor's legal name, a phone number, the business address, and the business-name registration number if the name is registered (the EU trader rules ask for a trade-register number where one exists).

**What becomes public.** The listing shows the trader's real name, address, telephone number and email. A home address is shown if that is the business address; use a business address you are happy to publish. The documents in this folder name the business and the proprietor but do not repeat the postal address, which stays on the store listing.

**Keep consistent**
- The proprietor's legal name and the trade name appear in the Terms of Use and the Privacy Policy exactly as they appear in the store accounts, and the registered business name is spelled the same way everywhere.
- Use the same contact email in both stores, the documents and the support URL. Check the contact address works.
- The trader declaration includes a self-certification that you offer only products that comply with the law. Keep it true, and update the stores if your name, address, phone number or registration changes.
- Runout stays free, with no adverts, purchases or revenue. If that ever changes, update the Terms of Use and the Privacy Policy, review the store forms (section 6), and tell your accountant.

## 6. What changes when each planned feature ships

Update the privacy policy, this file, the in-app permission text and both store forms in the same release.

| Planned feature | Privacy policy | Google Play | Apple App Store |
|---|---|---|---|
| **Live camera** (instead of the system camera app) | Describe the camera use: frames are analysed in memory on the device and not saved or sent. | Add `CAMERA` to the manifest (runtime permission). Data safety stays "no data collected" if frames never leave the device. | Add `NSCameraUsageDescription` to `Info.plist` with a plain reason. App Privacy stays "Data Not Collected" if nothing leaves the device. |
| **Microphone sound-envelope analysis** (break speed) | Describe it: the microphone is used only while the meter runs, a loudness envelope is analysed in memory, and no audio is recorded, stored or sent. | Add `RECORD_AUDIO` (runtime permission). Data safety stays "no data collected" if audio never leaves the device. Explain the use in the review notes. | Add `NSMicrophoneUsageDescription`. App Privacy stays "Data Not Collected". Explain the use in the review notes. |
| **On-device ball-detection model** | Say that detection runs on the device. No other change if nothing is sent. | No form change. | No form change. |
| **Model licence and training data** | Not applicable. | Not applicable. | Not applicable. Add the model's licence and source to the third-party notices, and confirm the training pictures were taken by you or licensed for this use. Broadcast screenshots must not be used as training data without permission. |
| **Practice history in a local database** | List what is stored (drill results, dates), where (private app storage), how long (until the user deletes it or uninstalls), and how to delete it. Add an in-app "delete my data" option. | No form change while the data stays on the device. If you turn on Android backup, the data would be copied to the user's own Google account backup, so update the policy first. | No form change while the data stays on the device. Backups made by the user may include it; say so in the policy. |
| **First-run acknowledgement** | Add: the app remembers that you accepted the Terms of Use and which version. | No form change. | No form change. |
| **Any network feature** (sync, analytics, crash reports, updates, adverts, accounts, purchases) | Full rewrite before release. | Data safety must declare each data type, purpose and whether it is shared; add the `INTERNET` permission. | App Privacy must list each data type; review App Tracking Transparency; review export compliance. |

## 7. Before each release

- Compare the manifest and `Info.plist` with the tables above.
- Check that every dependency and asset is listed in the third-party notices, and that the licence texts ship with the app.
- Re-read the privacy policy against what the code does, and update its effective date.
