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

These texts are written to be shown in the app. They are drafts until the placeholders `[Proprietor's legal name]` and `[Effective date]` are filled in everywhere they appear.

### First-run acknowledgement

Show it once, before the setup steps, as a full screen or a dialog that cannot be dismissed by tapping outside. The two links open the documents in the app or in the browser. Do not pre-tick or hide the agreement: the person must tap the agree button.

> **Before you start**
>
> Runout is a training aid. Its shot lines, speeds and plans are estimates, not guarantees, so please check anything it shows you before you rely on it. Please do not use it where electronic aids are not allowed.
>
> By tapping "I agree" you accept the **Terms of Use** and confirm you have read the **Privacy Policy**.

Buttons: **I agree** (primary) and **Not now** (closes the app or returns to the start screen without letting the person continue).

Store the acceptance on the device with the version or effective date of the terms (a new key beside the existing setup preferences) and show the screen again when the terms change materially. When this screen ships, add "that you accepted the Terms of Use, and which version" to the "Settings Runout remembers" list in the [privacy policy](privacy-policy.md).

### About / Legal summary

Place this on an "About" or "Legal" screen with links to the four documents and the licence texts.

> Runout is a free training aid made by Nexova Digital and published as open source under the MIT License. Its shot lines, speeds and plans are estimates and come with no guarantee, so check anything it shows you before you rely on it. Runout does not collect your information: nothing leaves your device and there are no accounts, adverts or analytics. Rules summaries are original summaries, not the official rules. Runout is not affiliated with any governing body, league, tour or equipment maker. Read the Terms of Use, Privacy Policy and Third-party notices from the links below. Questions: nexovadigital.info@gmail.com.

Also bundle the text of [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) and [licenses/BSD-3-Clause-Skia.txt](licenses/BSD-3-Clause-Skia.txt) in the app, so that a copy of each licence travels with the installed app, and make the privacy policy reachable from inside the app.
