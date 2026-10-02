#!/usr/bin/env bash
# Final on-phone checks of the installed build with the default models.
cd "$(dirname "$0")/.."
ADB=.tools/sdk/platform-tools/adb.exe
PY=.tools/asrvenv/Scripts/python
LOG=.tools/bench/final-log.txt
step() { echo "=== $(date +%T) $*" | tee -a "$LOG"; "$ADB" wait-for-device; }
keys='"(segments|final_wer|live_lag_ms_max|refine_behind_ms_median|refine_behind_ms_max|notes_sections_s|overviews_s|finish_after_stop_s|max_temp_c|max_pss_mb|facts|traps|invented_numbers|crash)"'

step "install"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -1 | tee -a "$LOG"
MSYS_NO_PATHCONV=1 "$ADB" shell "am force-stop dev.localnotes; svc power stayon usb; dumpsys package dev.localnotes | grep lastUpdateTime; dumpsys battery | grep -E 'level|temperature'" | tee -a "$LOG"

step "end-to-end meeting with gemma4-e2b"
$PY tools/phone_e2e.py --live kroko-en --accurate parakeet-unified --summary gemma4-e2b --wav meeting --tag "final-gemma-meeting" 2>&1 | grep -E "$keys" | tee -a "$LOG"

step "overlap with gemma4-e2b"
$PY tools/phone_e2e.py --live kroko-en --accurate parakeet-unified --summary gemma4-e2b --wav meeting --overlap --tag "final-gemma-overlap" 2>&1 | grep -E "OVERLAP|$keys" | tee -a "$LOG"

step "endurance 24 min with gemma4-e2b"
$PY tools/phone_e2e.py --live kroko-en --accurate parakeet-unified --summary gemma4-e2b --wav endurance --tag "final-gemma-endurance" 2>&1 | grep -E "$keys" | tee -a "$LOG"
echo "=== $(date +%T) DONE" | tee -a "$LOG"
