# Legal documents

The documents that go with Runout, the free pool and snooker training aid for Android and iOS.

| Document | What it is |
|---|---|
| [Terms of Use](terms-of-use.md) | The rules for using the app: what Runout is, that its shot lines and plans are estimates, your responsibility for how you use it, where the developer is and is not liable, and which law applies. |
| [Privacy Policy](privacy-policy.md) | What Runout does with your information. In short: nothing leaves your device and the developer receives nothing. |
| [Third-party notices](third-party-notices.md) | The open-source components inside Runout, their licences, and a note that Runout is independent of the organisations and brands it mentions. The licence texts are in [licenses/](licenses/). |
| [Store declarations](store-declarations.md) | A maintainer checklist: the answers for the Google Play and Apple App Store forms, and what must change when each planned feature ships. |

The MIT [`LICENSE`](../../LICENSE) at the top of the repository covers Runout's source code. The documents above cover use of the app. Nothing in them reduces the rights the MIT licence gives you in the source code.

Contact: nexovadigital.info@gmail.com, or the [issue tracker](https://github.com/skitzofrenzy/Runout/issues).

## For implementers

These texts are shown in the app and are built in. They are drafts until the placeholders `[Proprietor's legal name]` and `[Effective date]` are filled in everywhere they appear (set in `LegalConfig.kt`). The code is in `composeApp/src/commonMain/kotlin/com/runout/app/legal/`.

### How the app uses them

- **Bundling.** The build task `generateLegalDocuments` copies the terms, the privacy policy, the third-party notices and the two licence texts from this folder into the app (`LegalDocument` lists them), so the installed app carries the same text as the repository. A unit test (`BundledLegalDocumentsMatchSourceTest`) fails if the bundle and the source differ, and it also compares the quoted texts below with `LegalCopy` in `LegalDocuments.kt` character for character: change a quoted text here and in `LegalCopy` together.
- **Placeholders.** `LegalConfig.applyPlaceholders` fills in the developer name, the effective date and the proprietor's legal name from `LegalConfig`; a value that is unset leaves its bracketed placeholder visible. The task `verifyLegalPlaceholders` stops release builds (`assembleRelease`, `bundleRelease`, `packageRelease`, `packageReleaseBundle` and the release iOS frameworks) while a placeholder would still show; debug builds and tests are not affected.
- **Terms version.** `LegalConfig.TERMS_VERSION` is the version of the terms people accept. `TermsVersionPinTest` pins it to a hash of `terms-of-use.md`, so a change to the terms fails the test until the version is bumped (which makes the acknowledgement appear again) and the pin is updated.
- **Reading.** Documents open in the app (`LegalDocumentScreen`, a small Markdown reader in `LegalMarkdown.kt`); relative links between bundled documents open inside the app.

### First-run acknowledgement

`TermsAcknowledgementScreen` is a full screen shown once, before the setup steps, until the person agrees to the current terms version; it is not a dialog and cannot be dismissed from outside. The two links open the documents inside the screen, and Back from a document returns to the notice. The agreement is not pre-ticked: the person must tap **I agree**. The accepted version is stored with the setup choices (`SetupPreferencesStore`: `SharedPreferences` on Android, `NSUserDefaults` on iOS), and the screen shows again when `TERMS_VERSION` changes. **Not now** keeps the person on the screen with a short note, because the app cannot be used without agreeing. The privacy policy lists the stored acceptance under "Settings Runout remembers".

> **Before you start**
>
> Runout is a training aid. Its shot lines, speeds and plans are estimates, not guarantees, so please check anything it shows you before you rely on it. Please do not use it where electronic aids are not allowed.
>
> By tapping "I agree" you accept the **Terms of Use** and confirm you have read the **Privacy Policy**.

The buttons are **I agree** (primary) and **Not now**; the quoted text above is the screen's text.

### About / Legal summary

`AboutLegalScreen` ("About & Legal" in the side menu) shows this summary at the top (with the placeholders filled in from `LegalConfig`) and a list that opens each of the five bundled documents: the terms of use, the privacy policy, the third-party notices and the two licence texts. Back from a document returns to this screen.

> Runout is a free training aid made by Nexova Digital and published as open source under the MIT License. Its shot lines, speeds and plans are estimates and come with no guarantee, so check anything it shows you before you rely on it. Runout does not collect your information: nothing leaves your device and there are no accounts, adverts or analytics. Rules summaries are original summaries, not the official rules. Runout is not affiliated with any governing body, league, tour or equipment maker. Read the Terms of Use, Privacy Policy and Third-party notices from the links below. Questions: nexovadigital.info@gmail.com.

The text of [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) and [licenses/BSD-3-Clause-Skia.txt](licenses/BSD-3-Clause-Skia.txt) is bundled, so a copy of each licence travels with the installed app, and the privacy policy is reachable from inside the app (from the acknowledgement screen and from About & Legal).
