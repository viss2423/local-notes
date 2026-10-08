# Local Notes: product review, 8 October 2026

The strongest direction is a private recording workspace where users can find, check and act on what was said. A larger model alone will not deliver that. This review compares documented workflows, not measured competitor accuracy or speed. No competitor subscription or hardware was purchased or tested.

## What the comparison suggests

| Product and official evidence | Useful pattern | Local Notes decision |
| --- | --- | --- |
| [Otter conversation page](https://help.otter.ai/hc/en-us/articles/5093228433687-Conversation-Page-Overview) | Separate summary/transcript, action items linked to their source, editable text and synchronized playback | Existing tabs/editing/seek cover part of this. Source-linked decisions and action items are the highest-value next review feature. |
| [Notta recording details](https://support.notta.ai/hc/en-us/articles/37437047511195-Records-detail-page-screen-layout-and-functions-Notta-Web) | Playback speed, bookmarks, transcript block tools and summary templates | Prioritize bookmarks and playback speed, then a small set of useful templates. Avoid adding a crowded toolbar to every passage. |
| [Plaud text notes and images](https://support.plaud.ai/hc/en-us/articles/53693901860633-How-do-text-notes-and-images-help) | User-supplied context improves the usefulness of notes | Add optional meeting title, purpose and vocabulary before recording. Keep raw transcript separate from context so the model cannot silently treat user context as spoken evidence. |
| [Pocket summaries](https://docs.heypocketai.com/docs/features/productivity/summary) and [speaker management](https://docs.heypocketai.com/docs/features/ai/speaker-management) | Organized summaries and speaker names that carry across recordings | Local Notes currently renames speakers within a recording. Cross-recording voice identity needs opt-in local profiles and false-match testing before adoption. Pocket's own documentation also describes a three-hour speaker-detection limit; this is not a solved problem in every commercial product. |

## Shipped in this UI pass

- Replaced the oversized slogan and decorative idle waveform with a compact workspace and explicit Start recording action.
- Blue/slate light and dark palettes; consistent typography and smaller corner radii.
- Stable icon-and-label bottom navigation with accessibility selection state.
- Full-width reading tabs; less vertical space used by the live recording header.
- Library filters for pending/error recordings and sessions of at least 30 minutes. Search continues to match titles and dates; it is not full-library transcript search.
- Collapsed processing tools to make review less cluttered; existing retry controls stay visible for incomplete recordings.

## Priorities after this release

1. **Summary turnaround and factual coverage.** The previous 22-minute OnePlus replay needed roughly 14 minutes after Stop for summary completion. Profile prompt processing, output generation and redundant section work separately. Compare shorter structured prompts and smaller quantized models on the same fixture, with fact recall, incorrect claims, numbers/dates, negations, elapsed time and memory recorded. Adopt only a measured improvement; do not sacrifice details to make a stopwatch look better.
2. **Source-linked notes and bookmarks.** Store source segment IDs with notes; validate that citations exist. One tap should reveal the supporting text and audio. Manual bookmarks should survive reprocessing. Do not generate plausible-looking timestamps without validation.
3. **Transcript correction and speaker repair.** Add reassign/merge/split speaker controls with undo. Rename is already supported, but changing the name does not fix a wrong identity assignment. Measure speaker confusion and false extra speakers separately from word error rate.
4. **Search across recordings.** A background local text index should return recording, matched passage and seek position. Test edited/deleted recordings, interrupted indexing and many long sessions. Avoid reading every complete transcript on the UI thread.
5. **Capture feedback.** Show persistent quiet/clipped input guidance using calibrated signal measurements, with hysteresis. Test faint speech mixed with noise, distance, overlap and accents. Digitally reducing clean speech is useful but is not a substitute for a weak microphone signal with room noise.
6. **Small, purposeful templates.** Meeting, lecture and interview formats, with editable context and an explicit separation between concise overview and detailed notes. Evaluate omitted facts and invented assignments for each template.
7. **Release trust.** Dedicated release signing with secure key custody, reproducible dependency inventory, upgrade/recovery tests and an independent security review. The existing update-compatible debug signing key remains in use for this release; no claim of zero vulnerabilities is justified.

## Model decision

Retain the measured Kroko live + Parakeet accurate + Gemma summary defaults in this UI release. Prior EdAcc testing found different accent tradeoffs between Parakeet Unified and TDT v2; a tested Moonshine v2 configuration failed on longer utterances. Those results are historical evidence, not a universal ranking. A model change needs paired output and performance evidence, including OnePlus sustained performance. This pass does not change the audio, ASR, diarization or summary inference pipeline.

## Platform scope

This artifact is Android only. iOS requires a separate native audio/background implementation, model-runtime integration, signing and real-device validation. No Mac or iPhone is available in the current workflow. UI screenshots and host tests are not microphone or multi-hour endurance tests.
