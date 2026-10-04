# Local Notes v0.8 host validation — 4 October 2026

This update targets faint and varied-accent English speech and the failure modes of recordings lasting hours. The Android package still uses local models; the audio and transcript stay in the app's private storage unless the user exports them.

## Accent and quiet-input comparison

I sampled 48 real clips (about 6.7 minutes) from the University of Edinburgh's [EdAcc accented English corpus](https://groups.inf.ed.ac.uk/edacc/): six clips each of Indian, Nigerian, Jamaican, Kenyan, Eastern European, Vietnamese, Southern British, and Mainstream US English. There were 2–6 speakers per accent group. Each engine received the same 16 kHz audio. Quiet input was digitally attenuated by 26 dB (amplitude ×0.05). Word error rate (WER) is normalized with Whisper's English normalizer and macro-averaged across the eight groups. RTF is compute time divided by audio duration on the Windows CPU; it is **not** OnePlus speed.

| Model | Normal WER | Quiet raw WER | Quiet with current offline boost / former live boost | PC RTF, normal |
| --- | ---: | ---: | ---: | ---: |
| Kroko live | 22.63% | 23.25% | 26.29% (former 16× live ceiling) | 0.056 |
| Nemotron 560 ms live | 34.60% | — | 39.45% | 0.26–0.39 |
| Parakeet Unified offline | 12.37% | 12.60% | 12.14% | 0.07–0.09 |
| Parakeet TDT v2 offline | **12.09%** | **11.55%** | **10.91%** | 0.065–0.069 |
| Qwen3-ASR 0.6B offline | 12.65% | 12.81% | 12.29% | about 0.18 |

The live 16× input gain hurt this sample. A separate sweep at fixed 2×, 4×, 8×, 12×, and 16× gain gave quiet macro WER of 22.55%, **22.52%**, 22.60%, 22.64%, and 22.91%. The app's adaptive gain rule capped at 4× scored 22.62% quiet and 23.64% normal, versus 26.29% quiet and 23.98% normal with the former 16× cap. The live ceiling is now 4×; the voice detector retains stronger gain to catch faint speech. Saved PCM remains unaltered. This evidence concerns digitally reduced volume with preserved signal-to-noise ratio; speech below a noisy microphone's noise floor cannot be recovered by gain alone.

TDT v2 is now an optional, hash-pinned accuracy model in Setup, while Unified remains the default. The overall difference was small and varied by accent: Unified was better for Jamaican and Nigerian clips; TDT v2 was much better for the Vietnamese clips. A prior five-domain phone benchmark found both Parakeet versions close, so there is no basis to claim a universal winner. The larger Qwen3-ASR model did not win this sample and was slower, so it is not shipped. Sources: [EdAcc corpus](https://groups.inf.ed.ac.uk/edacc/), [sherpa-onnx Parakeet models](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/index.html), [sherpa-onnx Qwen3-ASR documentation](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html).

## Hours-long processing and recovery

The previous overview prompt concatenated all detailed notes and could exceed the native model's input limit after a long recording. Overview generation now uses bounded source batches, saves progress after each successful batch, and resumes on reopen. A host test created twelve 600-word detailed sections, forced a failure after two batches, and verified that resumption covered all twelve without a large prompt.

When Android kills the recorder process, the saved PCM can now be queued for full transcription when the app reopens. The recovered end time uses the recorded start, sample count, and pauses. A unit test created a sparse three-hour PCM file with a five-minute pause and checked that audio length, interrupted status, end timestamp, and queue marker survived recovery. A 3h13m synthetic WAV fixture and background-run phone harness are prepared for the later real-time OnePlus endurance test.

Host validation: 27 Android/Robolectric unit tests passed, debug build and lint analysis passed, and the phone benchmark harness imports and parses its command line. The first sandboxed test attempt could not fetch Robolectric runtime artifacts; the same tests passed with network access. A full three-hour foreground-service, battery/thermal, microphone, and screen-off run still needs the reconnected OnePlus; the sparse-file test does not replace it.
