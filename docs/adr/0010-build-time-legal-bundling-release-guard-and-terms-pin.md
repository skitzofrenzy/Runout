# ADR 0010: Build-time legal document bundling, release placeholder guard, and terms version pin

- **Status:** Accepted
- **Date:** 2026-11-25

## Context

Runout is a 100% offline Android and iOS app that must present its Terms of Use, Privacy Policy, Third-party notices, and open-source licence texts inside the installed binary, and require an explicit first-run acknowledgement before the app is used.

Hand-copying legal texts into Kotlin files or platform-specific resources creates three failure modes:

1. **Source drift:** App text quietly diverges from the canonical documents in `docs/legal/`.
2. **Shipping draft placeholders:** Unfilled metadata such as `[Effective date]` or `[Proprietor's legal name]` could accidentally be packaged into a release build.
3. **Silent terms changes:** Editing `docs/legal/terms-of-use.md` without bumping the accepted terms version would leave existing users bound to updated terms they were never asked to re-acknowledge.

## Options Considered

- **Option A — Fetch legal documents from a web URL at runtime:** Violates Runout's offline guarantee and fails without network access.
- **Option B — Hand-maintained Kotlin string constants or separate Android/iOS raw assets:** Duplicates text across `docs/legal/` and code, risks JVM string-constant size limits on full licence texts, and provides no release-time check against unresolved placeholders.
- **Option C — Build-time Kotlin source generation from `docs/legal/`, a release-only placeholder guard task, and a SHA-256 unit-test pin on `TERMS_VERSION` (Selected):** Keeps `docs/legal/` as the single source of truth for both platforms and turns drift, draft release builds, and unversioned terms edits into automated build/test failures.

## Decision

### 1. Build-time bundling from `docs/legal/` (`GenerateLegalDocumentsTask`)

- `docs/legal/` is the single canonical location for the five bundled files: `terms-of-use.md`, `privacy-policy.md`, `third-party-notices.md`, `licenses/Apache-2.0.txt`, and `licenses/BSD-3-Clause-Skia.txt`.
- In `composeApp/build.gradle.kts`, the Gradle task `generateLegalDocuments` (`GenerateLegalDocumentsTask`) reads those five files and generates `BundledLegalSources.kt` under `build/generated/legal/commonMain/kotlin` (wired into `commonMain.kotlin.srcDir`). Each document is emitted as a `List<String>` of escaped lines so no single string literal can exceed JVM class-file limits and both Android and iOS share the identical bundle.
- `LegalDocument` (`LegalDocuments.kt`) enumerates the five bundled documents with their `path` and `LegalDocumentFormat` (`MARKDOWN` or `PLAIN_TEXT`), applies `LegalConfig.applyPlaceholders` in `text()`, and resolves intra-document links via `LegalDocument.fromLinkTarget` for in-app display (`LegalDocumentScreen`, `LegalMarkdown.parse`).
- `BundledLegalDocumentsMatchSourceTest` asserts that every `LegalDocument` entry matches the corresponding file in `docs/legal/` (LF-normalized) and that the summary and acknowledgement strings in `LegalCopy` match the quoted copy in [docs/legal/README.md](../legal/README.md).

### 2. Placeholder substitution and release-only guard (`LegalConfig`, `VerifyLegalPlaceholdersTask`)

- `LegalConfig` (`LegalConfig.kt`) holds `DEVELOPER_NAME`, `PROPRIETOR_LEGAL_NAME`, and `EFFECTIVE_DATE` alongside their bracketed placeholder literals (`DEVELOPER_NAME_PLACEHOLDER`, `PROPRIETOR_LEGAL_NAME_PLACEHOLDER`, `EFFECTIVE_DATE_PLACEHOLDER`). `LegalConfig.applyPlaceholders` leaves any `null` or blank setting as its visible bracketed placeholder so unconfigured drafts never display invented metadata.
- The Gradle task `verifyLegalPlaceholders` (`VerifyLegalPlaceholdersTask`) reads `LegalConfig.kt` and all bundled files in `docs/legal/`, applies the same non-blank substitution rule, and fails the build if any bracketed placeholder remains.
- `verifyLegalPlaceholders` is attached only to release tasks (`assembleRelease`, `bundleRelease`, `packageRelease`, `packageReleaseBundle`, and `linkRelease*` iOS framework tasks), so debug builds and unit tests remain usable while placeholders are still being finalised.

### 3. Terms version pin and first-run re-acknowledgement (`LegalConfig.TERMS_VERSION`, `TermsVersionPinTest`)

- `TermsAcknowledgementScreen` blocks app entry until the user taps "I agree" and persists `LegalConfig.TERMS_VERSION` through `SetupPreferencesStore.saveAcceptedTermsVersion` (`TermsAcceptanceKeys.KEY_TERMS_VERSION`, checked by `isTermsAcceptanceCurrent`). Changing `TERMS_VERSION` automatically prompts existing users to review and agree again.
- `TermsVersionPinTest` computes the SHA-256 hash of `docs/legal/terms-of-use.md` (LF-normalized, trailing newlines trimmed) and asserts it equals `PINNED_TERMS_SHA256` and that `LegalConfig.TERMS_VERSION` equals `PINNED_TERMS_VERSION`. Any edit to `terms-of-use.md` fails the test suite until the maintainer decides whether to bump `TERMS_VERSION` and updates the pin.

## Consequences

- **Positive:** Android and iOS builds always embed the exact repository texts offline with zero manual duplication; release builds cannot ship unresolved placeholders; changes to the Terms of Use cannot silently skip user re-acknowledgement.
- **Trade-offs:** `VerifyLegalPlaceholdersTask` parses `LegalConfig.kt` with a deliberately simple regex, so the configured value and placeholder declarations must remain plain `val` / `const val` string literals or `null`; edits to `docs/legal/terms-of-use.md` or quoted copy in `docs/legal/README.md` must update `TermsVersionPinTest` or `LegalCopy` in the same pull request.

## Code References

- `composeApp/build.gradle.kts` (`GenerateLegalDocumentsTask`, `generateLegalDocuments`, `VerifyLegalPlaceholdersTask`, `verifyLegalPlaceholders`, `legalDocumentPaths`, `releaseEntryPoints`)
- `composeApp/src/commonMain/kotlin/com/runout/app/legal/LegalConfig.kt` (`LegalConfig`, `TERMS_VERSION`, `DEVELOPER_NAME`, `PROPRIETOR_LEGAL_NAME`, `EFFECTIVE_DATE`, `applyPlaceholders`)
- `composeApp/src/commonMain/kotlin/com/runout/app/legal/LegalDocuments.kt` (`LegalDocument`, `LegalDocumentFormat`, `LegalCopy`)
- `composeApp/src/commonMain/kotlin/com/runout/app/legal/LegalMarkdown.kt` (`LegalMarkdown.parse`), `composeApp/src/commonMain/kotlin/com/runout/app/legal/LegalScreens.kt` (`TermsAcknowledgementScreen`, `AboutLegalScreen`, `LegalDocumentScreen`)
- `composeApp/src/commonMain/kotlin/com/runout/app/storage/SetupPreferencesStore.kt` (`SetupPreferencesStore`, `TermsAcceptanceKeys`, `isTermsAcceptanceCurrent`)
- `composeApp/src/androidUnitTest/kotlin/com/runout/app/legal/BundledLegalDocumentsMatchSourceTest.kt`, `composeApp/src/androidUnitTest/kotlin/com/runout/app/legal/TermsVersionPinTest.kt`
- `docs/legal/README.md`
