# v0.11 workspace release — 8 October 2026

## Scope

UI and review workflow changes only. Models, capture, storage format, diarization and summarization inference are unchanged. New versionCode 11 / versionName 0.11.0.

The home screen has an explicit record action and recent recordings; the navigation has icons and selection semantics; recording and reading layouts use consistent blue/slate themes. Library filters expose pending/error sessions and recordings of at least 30 minutes. Processing tools are collapsible. Text action touch targets are 48 dp, and detail footer actions wrap.

## Verification record

- Reviewed host-rendered light home, dark home with full navigation, detail overview and 150% text detail. The first enlarged-text review exposed a narrow playback hint, corrected to a stacked layout.
- Existing transcript search, speaker labels, live/partial notes and summary rendering remain in the screenshot suite.
- Added library filter, navigation, smaller-screen record action, dark workspace and enlarged-text checks.
- Initial restricted build failed on Gradle cache access. A cache-enabled build exposed one navigation test failure. A focused rerun then encountered inconsistent Kotlin incremental outputs; subsequent builds disable incremental compilation and use the in-process compiler. No dependency versions were changed.
- Final test, lint, APK and signature results are recorded below after completion.

## Limits

No phone installation, microphone capture, on-device navigation or endurance test was performed for v0.11. Robolectric checks are host tests. The documented v0.9 14-minute post-stop summary delay remains unresolved; this release makes no faster-model or improved-accent claim.

The APK retains the existing debug signing certificate for in-place upgrades. It is a minified release build, not a debug build. A separate release-key strategy is a future product hardening task.

See [product review and priorities](PRODUCT_REVIEW_2026-10-08.md) for the official-source comparison with Otter, Notta, Plaud and Pocket. Competitor features were researched, not benchmarked.

## Final results (added 8 October 2026, after the original run was cut off)

- Host tests on the committed source (HEAD 170ab2b): 37 tests passed, 0 failed, 0 skipped (ChunkingTest 5, PipelineTest 19, UiScreenshotTest 13).
- Lint and the minified release build were run before the original run ended; the release APK dist/LocalNotes-0.11.0.apk (versionCode 11, 38,847,255 bytes) was built at 17:55 and its final lint result was not re-checked here.
- Installed over v0.10.0 on the OnePlus 13R with `adb install -r` (Android accepts an in-place update only when the signing key matches). The app launched without a crash and the 34 existing recordings were still listed. No microphone capture, endurance or summary-time test was run on this version.
