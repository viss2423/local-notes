# Local Notes v0.9 host validation — 5 October 2026

This update improves review and recovery of saved meetings. It does not change the speech or summary models benchmarked in v0.8.

## Changes

- A recording can be played inside its detail screen. The seekable timeline, ten-second jumps, and transcript-row taps seek to the matching audio offset. Playback presents the existing PCM as WAV through a 44-byte in-memory header and random file reads; it does not duplicate hours of audio.
- Recording lists read lightweight metadata first. Transcript and long detailed-note content load only when needed, and detailed-note lines render lazily. Removed periodic full-library refresh; saved transcript edits now refresh immediately.
- A failed transcription or summary keeps its pending marker and shows **Needs attention** with **Continue**. Automatic draining skips failed work until the user retries, avoiding a repeated failure loop.
- Unfinished live words now say **Identifying speaker** until an utterance actually receives a voice label. First-run setup shows the size calculated from recommended model archives rather than the stale 1.8 GB estimate.

## Host checks

Android/Robolectric: 29 tests passed (5 chunking, 17 pipeline, 7 UI), including a sparse three-hour PCM random-read test, WAV header checks, processing status transitions, and detail-screen rendering with playback controls. Debug lint analysis and a minified release build passed. Rendered summary and transcript screens were inspected; the timeline shows its thumb at the start and the correct 46:12 duration in the fixture.

The final APK is `dist/LocalNotes-0.9.0.apk` (38,830,871 bytes), package `dev.localnotes`, version code 9, version name 0.9.0. SHA-256: `56759917103B956887600BA42B5587DD32834CBAA5A977DAFD5F88CC5840CF83`. Android `apksigner` verified its v2 signature; signer certificate SHA-256 `638371cd162ac48cbefc889246f32fbf88db427cdda7f148bbdd53ceb42fccc6` matches v0.8, so the update can install over the existing app without removing its private recordings.

These checks validate the PCM/WAV data source and rendered controls on the host. Subsequent release playback and real-time synthetic recording tests on the OnePlus are documented in [v0.9 phone validation](VALIDATION_2026-10-05_V0.9_PHONE.md). Several-hour reliability, live microphone speech, sustained display-off behavior, and iOS remain untested.

Playback uses Android's [MediaDataSource](https://developer.android.com/reference/android/media/MediaDataSource) and [MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer) APIs. Previous accent and quiet-speech measurements are in [v0.8 validation](VALIDATION_2026-10-04_V0.8.md).
