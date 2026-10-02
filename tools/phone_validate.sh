#!/usr/bin/env bash
# Full on-phone validation, in order; each step waits for the phone (USB drops are common).
# Results: .tools/bench/phone/llm-phone-results.json and .tools/bench/e2e/e2e-results.json, log below.
cd "$(dirname "$0")/.."
ADB=.tools/sdk/platform-tools/adb.exe
PY=.tools/asrvenv/Scripts/python
LOG=.tools/bench/validate-log.txt
step() { echo "=== $(date +%T) $*" | tee -a "$LOG"; "$ADB" wait-for-device; }

step "install debug build"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -1 | tee -a "$LOG"
MSYS_NO_PATHCONV=1 "$ADB" shell "svc power stayon usb; dumpsys battery | grep -E 'level|temperature'" | tee -a "$LOG"

for model in qwen35-2b gemma4-e2b; do
  for src in e2e script; do
    for s in final1 final2 final3; do
      step "summary trial $model $src $s"
      $PY tools/phone_bench.py llm $model --threads=4 --source=$src --sampling=$s 2>&1 | grep -E "^==|Traceback" | sed "s/^/$model $src $s /" | tee -a "$LOG"
    done
  done
done

for model in qwen35-2b gemma4-e2b; do
  step "end-to-end meeting with $model"
  $PY tools/phone_e2e.py --live kroko-en --accurate parakeet-unified --summary $model --wav meeting --tag "v6-$model-meeting" 2>&1 | grep -E '"(final_wer|live_lag_ms_max|refine_behind_ms_max|finish_after_stop_s|max_temp_c|max_pss_mb|facts|traps|invented_numbers|crash)"' | tee -a "$LOG"
done

step "overlap: record again while the first summary is written"
$PY tools/phone_e2e.py --live kroko-en --accurate parakeet-unified --summary qwen35-2b --wav meeting --overlap --tag "v6-overlap" 2>&1 | grep -E 'OVERLAP|"(final_wer|crash)"' | tee -a "$LOG"

step "endurance: 24 minutes"
$PY tools/phone_e2e.py --live kroko-en --accurate parakeet-unified --summary qwen35-2b --wav endurance --tag "v6-endurance" 2>&1 | grep -E '"(final_wer|live_lag_ms_max|refine_behind_ms_median|refine_behind_ms_max|finish_after_stop_s|max_temp_c|max_pss_mb|crash)"' | tee -a "$LOG"
echo "=== $(date +%T) DONE" | tee -a "$LOG"
