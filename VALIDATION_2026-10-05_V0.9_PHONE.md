# Local Notes v0.9 OnePlus 13R validation — 5 October 2026

Tested on the connected OnePlus 13R (CPH2691). The signed 0.9.0 release installed over 0.7.0 without losing any of the 33 pre-existing recordings. Its detail screen played a saved 4:04 recording, advanced normally, sought to 2:04 from the timeline, and sought to 0:09 when a transcript passage was tapped. The release app opened without an AndroidRuntime or MediaPlayer error.

For repeatable speech measurements, I installed the matching 0.9.0 debug build with the same signing certificate and used its test-only real-time WAV replay hook. Kroko supplied live words, Parakeet Unified refined the transcript, and Gemma 4 E2B wrote notes and the overview on the phone. I restored the signed, non-debuggable release afterward.

| Real-time test | Normal 4:04, background | Quiet 4:04, foreground | 22:14, background |
| --- | ---: | ---: | ---: |
| Saved PCM bytes | 7,820,432 | 7,820,432 | 42,691,200 |
| Refined segments | 54/54 | 56/56 | 298/298 |
| Final word error rate | 11.20% | 11.86% | not scored |
| Median / maximum live lag | 0.3 / 0.6 s | 0.5 / 1.1 s | 0.5 / 1.7 s |
| Maximum accuracy-pass delay | 6.1 s | 6.2 s | 13.1 s |
| Full summary job after Stop | 152.7 s | 178.3 s | 843.2 s |
| Maximum battery temperature | 35.7°C | 35.7°C | 40.4°C |
| Crashes | 0 | 0 | 0 |

The four-voice meeting tests found all four speakers. Normal replay left one of 44 known turns unassigned and reached 98.9% pairwise speaker agreement; quiet replay assigned all 44 and reached 97.8%. Normal concise summary retained 22/33 checked facts and detailed notes 25/33; quiet concise summary retained 17/33 and detailed notes 29/33. No scored trap claims appeared. The numeric scorer's `17`/`08` flags came from generated wall-clock headings, rather than invented meeting numbers. The quiet file was digitally attenuated by 26 dB; this does not reproduce a distant microphone with a poor signal-to-noise ratio.

The background meeting showed an OxygenOS recording capsule with elapsed time, and Android reported the microphone foreground service and promoted ongoing notification. During the longer replay, saved PCM length and `capturedSamples` agreed exactly with 22:14.100 duration, and stored start/end epochs differed by that same amount. All 298 transcript segments were covered by eight detailed-note sections. The summary job finished, `summary-pending` cleared, and the app recorded no crash. The 14-minute post-recording summary time is a measured speed limit for this model on a 22-minute fixture.

The prepared 3h13m fixture was stopped early at the user's request because waiting three hours was unnecessary for this feature check. Consequently, this run does **not** establish several-hour reliability. The phone's “stay awake while charging” setting and harness polling prevented a sustained display-off test. The setting and original brightness were restored. No live microphone speech or iOS behavior was measured in these synthetic replays.

Only the three newly created synthetic test recordings and the replay WAV were removed afterward; 33 older recordings remained. The final installed app is the signed 0.9.0 release, and `run-as` confirms it is non-debuggable. Host checks and APK hash are in [v0.9 host validation](VALIDATION_2026-10-05_V0.9.md).
