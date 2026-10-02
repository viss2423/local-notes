# Local Notes

An offline Android recorder for long English meetings. It saves the original audio, displays live words, improves finished paragraphs with a larger speech model, labels voices, and writes detailed notes plus a concise summary. No account, subscription or API key. The internet permission is used to download model files; recording and inference stay on the phone.

Designed and tested on a OnePlus 13R. Other arm64 Android 12+ phones may be slower.

## Install and use

1. Install the latest APK over the existing app. **Do not uninstall the old app**: Android deletes its private recordings on uninstall. The update uses the same signing key.
2. In **Setup**, download the recommended models once: Kroko (live words, 57 MB), Parakeet (accuracy pass, 501 MB), Voice ID (speaker labels, 26 MB), and Gemma 4 E2B (notes and summaries, 3.3 GB). Downloads resume if interrupted. A smaller Qwen3.5 model is optional for summaries, but was less reliable with numbers in phone tests.
3. Tap **Start recording**. Live draft words appear while you speak; finished utterances receive a speaker label. Tap any label to rename it. No name prompt interrupts recording.
4. **Pause** or **Stop & save**. The recording is kept with its date, start/end time and pause history. You can view the transcript and export a ZIP containing the audio and text.

The app uses a microphone foreground service when you leave it. On Android 16 it requests a promoted ongoing notification with a status-bar chip; the phone's OxygenOS version and notification settings decide whether a Fluid Cloud-style capsule is shown. The recording notification and Stop action remain available even if the system does not promote it.

## Processing

| Stage | Engine | Behavior |
| --- | --- | --- |
| Capture | Android AudioRecord | 16 kHz mono PCM, about 115 MB/hour; storage sync about every 5 seconds. |
| Live speech | sherpa-onnx Kroko | Partial words update during speech; utterances close at a pause. |
| Accuracy pass | sherpa-onnx Parakeet Unified 0.6B int8 | Rechecks each short paragraph behind the live text. Paragraphs never merge across detected speakers. |
| Voice ID | 3D-Speaker ERes2Net English embedding model | Compares finished utterances locally; labels Speaker 1, Speaker 2, etc. in order of first detection. User names are stored separately. |
| Notes and summary | llama.cpp + Gemma 4 E2B | Writes section notes and a concise overview, with a number guard that removes unsupported numeric claims. |

Speaker detection needs a clean enough utterance and can make mistakes on overlap, noise, very short turns, or similar voices. The live label for unfinished words is provisional. Renaming a speaker changes its display name, not the recognized words. The accurate model improves different accents but no offline ASR can guarantee every word; check important names and numbers against the saved audio.

Start time comes from the first captured audio frame where available. End time is the last captured frame, or the Stop press if paused. Pause periods are stored so transcript wall-clock times map back to when words were spoken.

In a four-voice, 44-turn phone replay, the acoustic speaker boundary correctly separated the known turns into four labels. Its first version produced 17.13% final word error because the live model echoed some words into silent fragments; the current build filters those fragments and still needs a final on-phone replay. A deliberately quiet replay of that first version (-26 dB relative level) had 12.12% word error but labeled only 5 segments, which prompted bounded input gain in the current build. These fixtures do not establish accuracy for all accents, quiet rooms, overlapping voices, or several-hour conversations. See the dated notes in [PROGRESS.md](../PROGRESS.md) for validation details; [BENCHMARK_REPORT.md](BENCHMARK_REPORT.md) records earlier v0.4 Whisper experiments and is historical.

## Build

`python tools/bootstrap.py` installs portable tooling under `.tools/` and fetches the pinned sherpa-onnx Android library. Use `tools/build.ps1 -Setup` once for the SDK and NDK, then `tools/build.ps1 -Tasks testDebugUnitTest,assembleRelease,lintDebug`.

Models are downloaded by the installed app and are not included in the APK or repository. Release APKs from this workspace use the existing debug signing key so they install over earlier local builds. Keep that key safe; a different key requires an uninstall.

## License

App code: MIT ([LICENSE](LICENSE)). sherpa-onnx and the downloadable 3D-Speaker model have their own open-source licenses; model terms and notices should be checked before redistribution.
