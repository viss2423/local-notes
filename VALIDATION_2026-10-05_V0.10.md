# Local Notes 0.10.0 — computer-only validation (5 October 2026)

The OnePlus 13R was not connected for this release. All new checks ran on a Windows host. The v0.9 phone replay and playback results remain the hardware baseline; they do not establish that the changed v0.10 UI and processing have been exercised on the phone. The user can install the signed APK over v0.9 without uninstalling or deleting private recordings.

## Changes

- Replaced the generic home card with an audio-console layout, teal/coral studio palette, signal mark, more legible recording timer and waveform, and matching saved-audio player colours. The speaker rename affordance uses a real icon rather than a font glyph.
- Saved transcripts can now be searched by spoken text or the locally assigned speaker name. Matching passages keep their tap-to-play behavior. A long recording opens already-saved detailed notes while its concise summary is still pending, with approximate section progress while notes are generated.
- The summary cleaner no longer discards a complete final bullet just because it lacks sentence punctuation. It still removes a clearly dangling line ending in a connective (for example, “done by”). This protects action items that otherwise vanished from the detailed notes or concise summary.
- Setup now describes the tested Parakeet choice and the optional TDT v2 accent alternative, and correctly says that long summary jobs can take minutes after Stop.
- Full Android lint identified two Android 12/12L compatibility errors that the earlier analysis-only task missed: the saved-file speech splitter and test WAV reader called `InputStream.readNBytes`, introduced in API 33 although the app supports API 31. Both now use a short-read-safe block reader; an odd final PCM byte is ignored. A regression test covers short reads and EOF.

## Offline model decision

Model selection used the same 48 real utterances from the [University of Edinburgh EdAcc dataset](https://huggingface.co/datasets/edinburghcstr/edacc) as the v0.8 benchmark, six clips from each of eight accent groups. The digitally quiet condition multiplies the waveform by 0.05 and preserves its signal-to-noise ratio; it is not a distant microphone test. Word error uses the Whisper English text normalizer. Results are PC CPU measurements, not OnePlus timing.

| Accuracy model | Normal macro WER | Quiet + gain macro WER | Decision |
| --- | ---: | ---: | --- |
| Parakeet Unified int8 | 12.37% | 12.14% | Retain default; measured on the OnePlus in v0.9. |
| Parakeet TDT v2 int8 | 12.09% | 10.91% | Keep optional; improved this sample overall but lost to Unified on some accent groups. |
| Qwen3-ASR 0.6B int8 | 12.65% | 12.29% | Rejected in v0.8; slower on PC than Parakeet. |
| Moonshine v2 Base English | 64.30% | 63.09% | Rejected; the current sherpa/ONNX runtime hit decoder broadcast errors on multiple longer clips and returned empty text. |

The Moonshine result is a runtime/model combination failure, not a general quality claim about all deployments. The official [sherpa-onnx Moonshine v2 model page](https://k2-fsa.github.io/sherpa/onnx/moonshine/models-v2.html) lists a 135 MB English model and Android examples; the smaller download did not justify shipping a regression. [NVIDIA Parakeet TDT v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) expands multilingual support, which is not the app's English-only requirement. Earlier PC cross-domain live tests also put Kroko at mean 19.38% WER / 0.039 real-time factor and the more accurate Nemotron no-reset mode at 16.45% / 0.667 with four PC threads, too costly to substitute for the current tested live path without phone validation. A faster/larger on-device model has not been demonstrated by these results.

The v0.9 22-minute replay produced only two detailed-note sections during recording and required 843 seconds to complete the rest after Stop. This is the measured limitation of the current Gemma 4 E2B pipeline on the OnePlus. Switching to the optional smaller Qwen3.5 model reduced phone summary time in an earlier 4-minute fixture but retained fewer checked facts, so Gemma remains the recommended model. The v0.10 cleaner and progress UI improve information retention and visibility; they do not establish a faster post-Stop generation time. See [v0.9 phone validation](VALIDATION_2026-10-05_V0.9_PHONE.md) and the [earlier security/product audit](AUDIT_2026-10-04.md).

## Product comparison

The closest commercial workflows include live transcripts, speaker names, audio-linked review, highlights and vocabulary correction. [Pocket's speaker management](https://docs.heypocketai.com/docs/features/ai/speaker-management) describes persistent speaker identities; [Plaud's transcription workflow](https://support.plaud.ai/hc/en-us/articles/53793130069657-Transcribe-and-summarize) describes custom vocabulary, speaker names and templates; and [Plaud Highlights](https://support.plaud.ai/hc/en-us/articles/50609128298521-Highlights-Multimodal-Input) captures timestamped moments. Local Notes now offers private offline capture, live text, per-recording speaker names, searchable and audio-linked saved transcripts, and editable text without a subscription. It still lacks cross-recording identity, custom vocabulary, and one-tap highlights, and no controlled same-audio head-to-head test against those products was run. Those are product gaps, not claims of parity.

## Verification and limits

Android/Robolectric: **32 tests passed, zero failures/errors** (5 chunking, 19 pipeline, 8 UI), including short PCM reads, saved-note recovery, transcript search UI, rendering, playback and audio metadata. Full `lintDebug` passed with **zero errors and 15 warnings**; the warnings are library lint API compatibility, newer dependency advisories, inlined constants, ChromeOS ABI, an obsolete resource qualifier and storage-space guidance. The minified signed release built successfully. UI previews were inspected in light and dark modes, including the matching search result and pending-note views.

A static privacy review found only the launcher Activity exported; recorder, download and processing services are private. App backup and cleartext traffic are disabled, model downloads are HTTPS with pinned SHA-256 hashes, and a targeted source search found no embedded credential. This review does not prove the absence of all vulnerabilities. APK ZIP inspection found no bundled recordings or model weights.

No Android emulator image is installed in this workspace. This phase did not test the microphone, Fluid Cloud/status capsule, device temperature, accents heard through the phone microphone, or three-hour endurance. Those require hardware; v0.9's repeatable real-time replay results are the most recent phone evidence.

**Final APK:** `dist/LocalNotes-0.10.0.apk`, 38,847,255 bytes, SHA-256 `649719E96703BB5A339B8253B28AE5AB61AE5847B4E427940A9FE3967B24D9EF`. Android package `dev.localnotes`, version code 10/name 0.10.0, min API 31, target API 35, arm64-v8a. APK signature verification passed; certificate SHA-256 `638371cd162ac48cbefc889246f32fbf88db427cdda7f148bbdd53ceb42fccc6` matches v0.9, allowing an in-place update. The first certificate-tool invocation failed because `JAVA_HOME` was absent in that shell; invoking the same verifier through the bundled JDK succeeded for both APKs.
