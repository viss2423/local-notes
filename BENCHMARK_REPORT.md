# Local Notes 0.3 validation ? 2026-10-01

## What actually ran

Windows CPU, four inference threads, whisper.cpp v1.7.6 and llama.cpp b5046. Intel Family 6 Model 186 host, 20 logical processors. No GPU inference. The Android ARM64 native libraries were compiled but cannot execute on this Windows host; no phone was attached.

Speech fixture: 12 public LibriSpeech dummy clean clips concatenated with 0.25-second gaps, 133.47 seconds, 303 reference words. This is read English from a narrow speaker sample, not a meeting, accent, overlapping-speaker or long-duration benchmark. A second case adds deterministic 15 dB Gaussian noise. Assets and commands are reproducible with tools/benchmark_assets.py and tools/benchmark_asr.py (Python numpy, pyarrow and soundfile required).

| Speech model | Size | Clean run | Clean word error | Noisy run | Noisy word error |
|---|---:|---:|---:|---:|---:|
| Small English | 488 MB | 58.49 s | 7.6% | 28.40 s | 10.2% |
| Base English | 148 MB | 8.33 s | 11.6% | 8.48 s | 13.2% |
| Base English Q5_1 | 60 MB | 8.87 s | 10.2% | 7.53 s | 11.2% |
| Tiny English | 78 MB | 4.34 s | 12.5% | 3.80 s | 14.2% |

Word error uses lowercase word tokens, without spoken-number/title expansion; formatting such as Mr versus MISTER can count as an error. The quantized model's slightly lower errors here do not establish that quantization improves general accuracy. Small had fewer errors, while Base Q5_1 offers the selected speed/size tradeoff. Disabling fallback and reducing best-of did not improve speed meaningfully; the app retains the normal decoding behavior.

Two further clean runs: Small 63.57 / 29.27 seconds, Base Q5_1 7.72 / 8.07 seconds. Timing varies; do not interpret this as a guaranteed speedup on the phone. A 35-second digital-silence input produced no text (about 2.9 seconds engine time).

## App behavior checks

12 automated tests passed, none skipped: seven-hour section coverage and text partitioning (5), native-rendered Android UI/navigation/rename/read/record states (4), partial persistence/resume/model-independent reuse and subsecond padding (2), and actual CPU transcription through the app's TranscriptionStage (1). The last test uses the matching Windows CLI in place of Android JNI, processes the 133-second audio in three sections, checks readable timestamped output, and verifies that the second run invokes no speech engine. Measured section compute total about 12 seconds on this host; cache reuse about 0.03 seconds. It is a functional test, not a controlled performance comparison.

Android build succeeded; lint reported 0 errors and 9 warnings. ARM64 compilation enables ARMv8.2 FP16 and dot-product instructions. Upstream CMake's informational feature probe omits the cross-target flag and prints a host-CPU error; actual compiler commands include the Android AArch64 target and the requested architecture flags and compile successfully.

## Summary quality: known limitations

The actual Qwen 1.5B Q4_K_M model was run on a seven-turn, known-content meeting fixture. The initial prompt invented a meeting date/year and produced an incorrect conditional action. Revised prompts eliminated those errors on this fixture, shortened repetition, and preserved the launch correction, budget, interview/accessibility counts, provider uncertainty, conditional venue budget/cost, next meeting and failed-login issue.

However, the final detailed output still omitted Alex as the supplier-contract owner and omitted source timestamps. The concise output could change the future commitment into ambiguous past-tense wording. This is NOT a lossless or fully accurate summarization pass. Both AI output views must be checked against the retained full transcript/audio. Summary generation remains optional; no promise of complete information preservation is made. The initial summary pair took about 25 + 20 seconds on this PC; revised runs varied and overlapped compilation, so they are not controlled speed measurements.

## Still requires the OnePlus

Microphone/permission flow on real hardware, Android JNI runtime and cancellation, OxygenOS screen-off survival, battery/thermal behavior, several-hour recording continuity, and actual phone inference timings are not validated here. Run a short recording first, then a long acceptance recording before relying on the app. Export includes performance.json for measured phone transcription speed and cache reuse. No speaker diarization is implemented.

## Larger-model check (1 October 2026)

Same fixture, host and settings, run by tools/benchmark_more_models.py (log: more-model-benchmark-log.txt):

| Model | Size | Clean time | Clean WER | 15 dB time | 15 dB WER |
| --- | --- | --- | --- | --- | --- |
| Small English Q5_1 | 190 MB | 91.24 s | 7.9% | 34.67 s | 10.6% |
| Large-v3-turbo Q5_0 | 574 MB | 425.25 s | 5.9% | 274.54 s | 7.6% |

Large-v3-turbo is the most accurate, but it ran at roughly 2–3× slower than real time on this 20-thread PC. It cannot keep up with live speech on a phone CPU, and it would make final transcription of a multi-hour recording take many hours. Small Q5_1 is no faster than unquantized Small here. Base English Q5_1 remains the default. Any of these GGML files can still be imported for final transcription when accuracy matters more than time.

Live transcription in v0.4 also sets whisper.cpp `audio_ctx` to the clip length (50 encoder frames per second, plus 64 frames of headroom), instead of always encoding a padded 30-second window. For the 2–10 s live clips, this reduces encoder work by roughly 3–10×. It is applied only to the live preview; the final transcript keeps the full context. The speed gain has not been measured on the phone.
