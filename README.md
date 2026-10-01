# Local Notes

An offline voice recorder for Android with live transcription and on-device summaries. It saves the original audio, shows text while you speak, and can produce a final transcript plus a short summary and detailed notes. No account, ads, API key or subscription. The app has no internet permission.

Built for and tuned on a OnePlus 13R (Snapdragon 8 Gen 3, 12 GB RAM), English speech, recordings of several hours. Any 64-bit Android 12+ phone should run it; slower phones will show live text with more delay.

## How it works

| Stage | What happens | Engine |
| --- | --- | --- |
| Record | 16 kHz mono PCM written continuously (≈115 MB/hour), synced to storage every 5 s. Screen can be locked. | Android `AudioRecord` in a foreground service |
| Live text | Draft text for the current sentence refreshes about every 2 s. Every 6–10 s, at the quietest moment, a section is committed to `live-transcript.txt`. Silence is skipped. | whisper.cpp, Base English Q5 (60 MB), encoder context sized to each clip |
| Final transcript | Optional, after recording. 60 s sections, resumable, cached. More accurate than the live preview. | whisper.cpp, full 30 s context |
| Summary and notes | Optional. Section notes, then a concise summary built from them. | llama.cpp, Qwen2.5 1.5B Instruct Q4_K_M (1.12 GB) |

### Start and end times

The start time is the moment the first audio sample was captured, taken from Android's audio clock (`AudioRecord.getTimestamp`) rather than the moment the button was pressed. The end time is the moment the last sample was captured, or when you pressed Stop if the recording was paused. Pauses are logged, so every line of the transcript shows the time of day it was spoken. Everything is stored in `recording-info.json` as epoch milliseconds with time-zone IDs. Recordings are named by date and time (for example `Recording · 1 Oct 2026, 10:41`), and exports are named `2026-10-01 1041 <title>.zip`.

## Install

1. Install the APK. To update, install the new APK over the old one and **do not uninstall first**, or your recordings are deleted.
2. Open **Setup** → Speech recognition → **Download**, then **Import file** and choose `ggml-base.en-q5_1.bin` ([model file](https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-base.en-q5_1.bin)).
3. Optional: do the same for the summary model ([qwen2.5-1.5b-instruct-q4_k_m.gguf](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/main/qwen2.5-1.5b-instruct-q4_k_m.gguf)).
4. **Record**. Words appear a few seconds after they are spoken. **Stop & save** when done.
5. Open the recording → **Transcribe** for the final transcript, or **Transcribe and summarize**.
6. **Export** creates a ZIP with the WAV, timing metadata, live text, transcript, notes and performance measurements.

## Choosing a speech model

Measured on a 20-thread Intel PC, CPU only, with 133 s of clean English speech (LibriSpeech). Phones are slower. WER is token word error rate (lower is better):

| Model | Size | Time for 133 s | WER clean | WER 15 dB noise |
| --- | --- | --- | --- | --- |
| **base.en Q5_1** (default) | 60 MB | 7.5–8.9 s | 10.2 % | 11.2 % |
| small.en Q5_1 | 190 MB | 35–91 s | 7.9 % | 10.6 % |
| small.en | 488 MB | 28–64 s | 7.6 % | 10.2 % |
| large-v3-turbo Q5_0 | 574 MB | 275–425 s | 5.9 % | 7.6 % |

Larger models are more accurate but slower. Large-v3-turbo needed 3× real time even on the PC, so it is unsuitable for live text on a phone. Any whisper.cpp GGML model can still be imported and used for the final transcript. See [BENCHMARK_REPORT.md](BENCHMARK_REPORT.md) for method and limits. Faster engines for this hardware (for example NVIDIA Parakeet through sherpa-onnx, or the Snapdragon NPU through Qualcomm QNN) would need a separate inference stack and have not been integrated or tested.

## Limits

- **The audio is the source of truth.** Speech recognition misses words. The 1.5B summary model can omit facts, owners and timestamps, or change tense.
- Live text is a preview. The final transcript can differ.
- No speaker labels yet.
- Phone timing, multi-hour screen-off reliability, battery and heat have not yet been measured on a device. Before relying on it for a long meeting, test a few minutes with known names and numbers, including a locked screen and an incoming call.

## Build

Requirements are downloaded locally, with no system installs: `python tools/bootstrap.py` fetches a portable JDK 17 and Gradle 8.9 into `.tools/`, and `tools/build.ps1 -Setup` installs Android SDK 35, NDK 27.2 and CMake 3.22.1. Then:

```powershell
tools/build.ps1 -Tasks testDebugUnitTest,assembleDebug,lintDebug
```

Native engines are pinned to whisper.cpp v1.7.6 and llama.cpp b5046, with SHA-256 checks on the downloaded archives, and built with ARMv8.2 FP16 and dot-product support for arm64-v8a. Their license notices are bundled in `app/src/main/assets/licenses/`. Models and build tools are not part of this repository.

APKs built this way are debug-signed with a key that stays on the build machine. An update must be signed with the same key, or Android refuses to install it over the old version.

## License

MIT. See [LICENSE](LICENSE). whisper.cpp and llama.cpp are MIT-licensed. Model weights carry their own licenses: Whisper is MIT, and Qwen2.5 is Apache 2.0.
