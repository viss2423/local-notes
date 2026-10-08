# Local Notes

An offline Android recorder for long English meetings. It saves the original audio, displays live words, improves finished paragraphs with a larger speech model, labels voices, and writes detailed notes plus a concise summary. No account, subscription or API key. The internet permission is used to download model files; recording and inference stay on the phone.

Designed and tested on a OnePlus 13R. Other arm64 Android 12+ phones may be slower.

## Install and use

1. Install the latest APK over the existing app. **Do not uninstall the old app**: Android deletes its private recordings on uninstall. The update uses the same signing key.
2. In **Setup**, download the recommended models once: Kroko (live words, 57 MB), Parakeet Unified (accuracy pass, 501 MB), Voice ID (speaker labels, 26 MB), and Gemma 4 E2B (notes and summaries, 3.3 GB). Downloads resume if interrupted. Parakeet TDT v2 is an optional 482 MB accuracy model: it scored slightly better overall on a 48-clip varied-accent PC sample, though results varied by accent. A smaller Qwen3.5 model is optional for summaries, but was less reliable with numbers in phone tests.
3. Tap **Start recording** on Home. Live draft words appear while you speak; finished utterances receive a speaker label. Tap any label to rename it. No name prompt interrupts recording.
4. **Pause** or **Stop & save**. The recording is kept with its date, start/end time and pause history. Open it to play the saved audio, seek with the timeline, search the transcript for a word or speaker, or tap a passage to hear it. You can export a ZIP containing the audio and text.

The app uses a microphone foreground service when you leave it. On Android 16 it requests a promoted ongoing notification with a status-bar chip; the phone's OxygenOS version and notification settings decide whether a Fluid Cloud-style capsule is shown. The recording notification and Stop action remain available even if the system does not promote it.

Live inference has a 60-second audio backlog limit. If it falls too far behind or its model fails, the app keeps saving microphone audio and queues a full transcription from that saved file after Stop. An interrupted recording is recovered from its saved audio when the app reopens; the end time is reconstructed from captured samples and recorded pauses. Long summaries are processed in bounded batches and resume from the last saved batch. If processing fails, the recording shows **Needs attention** and **Continue** retries the saved work. A delayed transcript may temporarily be incomplete, but the preserved recording can be played or exported.

## Processing

| Stage | Engine | Behavior |
| --- | --- | --- |
| Capture | Android AudioRecord | 16 kHz mono PCM, about 115 MB/hour; storage sync about every 5 seconds. |
| Live speech | sherpa-onnx Kroko | Partial words update during speech; utterances close at a pause. |
| Accuracy pass | sherpa-onnx Parakeet Unified 0.6B int8; optional TDT v2 0.6B int8 | Rechecks each short paragraph behind the live text. Paragraphs never merge across detected speakers. |
| Voice ID | 3D-Speaker ERes2Net English embedding model | Compares finished utterances locally; labels Speaker 1, Speaker 2, etc. in order of first detection. User names are stored separately. |
| Notes and summary | llama.cpp + Gemma 4 E2B | Writes section notes and a concise overview, with a number guard that removes unsupported numeric claims. |

Speaker detection needs a clean enough utterance and can make mistakes on overlap, noise, very short turns, or similar voices. The live label for unfinished words is provisional. Renaming a speaker changes its display name, not the recognized words. The accurate model improves different accents but no offline ASR can guarantee every word; check important names and numbers against the saved audio.

Start time comes from the first captured audio frame where available. End time is the last captured frame, or the Stop press if paused. Pause periods are stored so transcript wall-clock times map back to when words were spoken.

On the OnePlus 13R, v0.9 completed normal and digitally quiet four-minute, four-voice replays without crashing, with 11.20% and 11.86% final word error respectively. All four voices were found in each. A separate 22-minute real-time background replay saved its full audio and timestamps, refined 298 transcript segments, and completed eight detailed-note sections plus a concise summary; the summary took 14 minutes after Stop. The phone also played, sought, and jumped from a transcript passage in the signed release. These synthetic replays do not establish distant-microphone accuracy or several-hour reliability. See [v0.9 phone validation](VALIDATION_2026-10-05_V0.9_PHONE.md) for measurements and limits; [BENCHMARK_REPORT.md](BENCHMARK_REPORT.md) records earlier experiments.

v0.10 adds a teal/coral recording studio, text and speaker search inside saved transcripts, and a note-cleaning fix that preserves a complete final bullet without punctuation. A newer compact Moonshine v2 speech model was evaluated on the same 48 accented clips but rejected after decoder errors and substantially higher word error. The v0.10 changes are validated on the computer; no v0.10 phone test is claimed. See [v0.10 validation](VALIDATION_2026-10-05_V0.10.md).

v0.11 replaces the studio home with a blue/slate workspace, icon navigation, full-width reading tabs and library filters for unfinished recordings and longer sessions. Text actions have larger touch targets; review tools are grouped under **Processing tools**. Speech and summary models are unchanged. See the [product comparison and prioritized improvements](PRODUCT_REVIEW_2026-10-08.md).

## Build

`python tools/bootstrap.py` installs portable tooling under `.tools/` and fetches the pinned sherpa-onnx Android library. Use `tools/build.ps1 -Setup` once for the SDK and NDK, then `tools/build.ps1 -Tasks testDebugUnitTest,assembleRelease,lintDebug`.

Models are downloaded by the installed app and are not included in the APK or repository. Release APKs from this workspace use the existing debug signing key so they install over earlier local builds. Keep that key safe; a different key requires an uninstall.

See [the 4 October audit](AUDIT_2026-10-04.md) for security changes, competitor feature comparison, tested quality limits and iOS feasibility.

## License

App code: MIT ([LICENSE](LICENSE)). sherpa-onnx and the downloadable 3D-Speaker model have their own open-source licenses; model terms and notices should be checked before redistribution.
