#!/usr/bin/env bash
# Overlap + endurance tests. Android won't start the microphone behind the lock screen, so this waits
# for the phone to be unlocked, keeps the screen on during the tests and restores the timeout afterwards.
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
ADB=.tools/sdk/platform-tools/adb.exe
PY=.tools/asrvenv/Scripts/python
LOG=.tools/bench/rest-log.txt
keys='"(segments|final_wer|live_lag_ms_max|refine_behind_ms_median|refine_behind_ms_max|notes_sections_s|overviews_s|summary_job_s|max_temp_c|max_pss_mb|crash)"'
"$ADB" wait-for-device
pgrep -f phone_rest.sh | grep -v "^$$$" > /dev/null 2>&1 || true
echo "=== $(date +%T) waiting for the phone to be unlocked" | tee -a "$LOG"
until "$ADB" shell "dumpsys window" 2>/dev/null | grep -q "isKeyguardShowing=false"; do sleep 10; done
old=$("$ADB" shell settings get system screen_off_timeout | tr -d '\r')
"$ADB" shell "settings put system screen_off_timeout 3600000; svc power stayon true; input keyevent KEYCODE_WAKEUP"
echo "=== $(date +%T) unlocked; screen timeout was $old ms" | tee -a "$LOG"
if [ "$1" = "install" ]; then "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -1 | tee -a "$LOG"; fi
"$ADB" shell "am start -f 0x30000000 -n dev.localnotes/.MainActivity" > /dev/null
echo "=== $(date +%T) overlap" | tee -a "$LOG"
$PY tools/phone_e2e.py --live kroko-en --accurate parakeet-unified --summary gemma4-e2b --wav meeting --overlap --tag final-gemma-overlap 2>&1 | grep -E "OVERLAP|$keys" | tee -a "$LOG"
echo "=== $(date +%T) endurance 24 min" | tee -a "$LOG"
$PY tools/phone_e2e.py --live kroko-en --accurate parakeet-unified --summary gemma4-e2b --wav endurance --tag final-gemma-endurance 2>&1 | grep -E "$keys" | tee -a "$LOG"
"$ADB" shell "settings put system screen_off_timeout $old; svc power stayon usb"
echo "=== $(date +%T) DONE (screen timeout restored to $old ms)" | tee -a "$LOG"
