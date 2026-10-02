"""End-to-end test of the installed debug app on the phone, using the real pipeline.

1. Installs models into the app's private storage (run-as; debug build) from /data/local/tmp/ln.
2. Starts a simulated recording that plays a test WAV at real-time pace (RecorderService debug hook).
3. Watches LocalNotesPerf logcat lines (live-text lag, accuracy-pass lag, notes/overview timing),
   battery temperature and app memory; takes screenshots of the live screen.
4. Pulls segments.json / summary.md / notes and scores WER (live + final) and summary facts.
Usage: python tools/phone_e2e.py --live kroko-en --accurate parakeet-unified --summary qwen35-2b [--wav meeting|endurance]
"""
from pathlib import Path
import argparse, json, re, subprocess, threading, time

ROOT = Path(__file__).resolve().parents[1]
BENCH = ROOT / '.tools/bench'
ADB = str(ROOT / '.tools/sdk/platform-tools/adb.exe')
PKG = 'dev.localnotes'
TMP = '/data/local/tmp/ln'
OUT = BENCH / 'e2e'; OUT.mkdir(exist_ok=True)

SOURCES = {  # app model id -> (folder under /data/local/tmp/ln, files to copy)
    'kroko-en': ('models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06', ['encoder.onnx', 'decoder.onnx', 'joiner.onnx', 'tokens.txt']),
    'nemotron-560': ('models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25', ['encoder.int8.onnx', 'decoder.int8.onnx', 'joiner.int8.onnx', 'tokens.txt']),
    'parakeet-unified': ('models/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming', ['encoder.int8.onnx', 'decoder.int8.onnx', 'joiner.int8.onnx', 'tokens.txt']),
    'parakeet-tdt-v2': ('models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8', ['encoder.int8.onnx', 'decoder.int8.onnx', 'joiner.int8.onnx', 'tokens.txt']),
    'silero-vad': ('models', ['silero_vad.onnx']),
    'qwen35-2b': ('llm', ['Qwen3.5-2B-Q4_0.gguf:model.gguf']),
    'qwen35-0.8b': ('llm', ['Qwen3.5-0.8B-Q4_0.gguf:model.gguf']),
    'gemma4-e2b': ('llm', ['gemma-4-E2B_q4_0-it.gguf:model.gguf']),
}

def sh(cmd, timeout=600):
    return subprocess.run([ADB, 'shell', cmd], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout).stdout

def require_device():
    found = subprocess.run([ADB, 'devices'], capture_output=True, text=True, timeout=15).stdout
    if '\tdevice' not in found:
        raise RuntimeError('ADB phone disconnected; reconnect before setting up the benchmark')

def run_as(cmd, timeout=1800):
    return sh(f"run-as {PKG} sh -c '{cmd}'", timeout)

def install_model(model_id):
    require_device()
    folder, files = SOURCES[model_id]
    if run_as(f'[ -f files/models/{model_id}/.complete ] && echo yes').strip() == 'yes':
        return
    print('install', model_id, flush=True)
    run_as(f'mkdir -p files/models/{model_id}')
    for f in files:
        require_device()
        src, dst = (f.split(':') + [f])[:2]
        # run-as can read /data/local/tmp; cat keeps the app as owner of the copy.
        run_as(f'cat {TMP}/{folder}/{src} > files/models/{model_id}/{dst}', timeout=3600)
    require_device()
    run_as(f'echo adb > files/models/{model_id}/.complete')

def choose(choices: dict):
    """Writes the app's model choice (SharedPreferences "models": role -> model id). App must be stopped."""
    xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" + ''.join(
        f'    <string name="{role}">{model_id}</string>\n' for role, model_id in choices.items()) + '</map>\n'
    local = OUT / 'models.xml'; local.write_text(xml, encoding='utf-8')
    subprocess.run([ADB, 'push', str(local), f'{TMP}/models.xml'], capture_output=True)
    run_as(f'mkdir -p shared_prefs && cat {TMP}/models.xml > shared_prefs/models.xml')

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--live', default='kroko-en'); ap.add_argument('--accurate', default='parakeet-unified'); ap.add_argument('--summary', default='qwen35-2b')
    ap.add_argument('--wav', default='meeting'); ap.add_argument('--tag', default='')
    ap.add_argument('--score-session', default='', help='only score an existing recording (its folder name)')
    ap.add_argument('--overlap', action='store_true', help='start a second recording right after the first stops (summary still running)')
    a = ap.parse_args()
    tag = a.tag or f'{a.live}+{a.accurate}+{a.summary}+{a.wav}'
    require_device()
    for m in [a.live, a.accurate, 'silero-vad', a.summary]:
        install_model(m)
    require_device()
    sh(f'am force-stop {PKG}')
    choose({'LIVE': a.live, 'ACCURATE': a.accurate, 'SUMMARY': a.summary})
    wav_local = BENCH / 'eval' / f'{a.wav}.wav'
    subprocess.run([ADB, 'push', str(wav_local), f'{TMP}/{a.wav}.wav'], capture_output=True)
    run_as(f'cat {TMP}/{a.wav}.wav > files/test.wav')
    sh(f'am force-stop {PKG}')
    # A large log buffer so the timing lines of a long run are not rotated out; read once at the end.
    sh('logcat -G 16M'); sh('logcat -c')
    # On-device recorders survive USB drops (the PC link is flaky): timing log and temperature/memory samples.
    sh('pkill -f sample.log; pkill -f "logcat -f"; rm -f /data/local/tmp/ln/perf.log* /data/local/tmp/ln/sample.log')
    sh("setsid nohup logcat -f /data/local/tmp/ln/perf.log -r 65536 -n 1 -v time LocalNotesPerf:I AndroidRuntime:E '*:S' > /dev/null 2>&1 &")
    sampler = ('while true; do echo "$(date +%T) $(dumpsys battery | grep -m1 temperature) $(dumpsys meminfo ' + PKG +
               ' | grep -m1 TOTAL)" >> /data/local/tmp/ln/sample.log; sleep 20; done')
    sh("setsid nohup sh -c '" + sampler + "' > /dev/null 2>&1 &")
    def sessions(): return set(run_as('ls files/sessions').split())
    def done(sid):
        files = run_as(f'ls files/sessions/{sid}').split()
        return 'summary.md' in files and not any('pending' in f for f in files) and run_as(f'cat files/sessions/{sid}/state.txt').strip() == 'done'
    before = sessions()
    t0 = time.time(); samples, shots, overlap_started, new = [], 0, 0.0, []
    if a.score_session:
        new = [a.score_session]
    else:
        sh('input keyevent KEYCODE_WAKEUP'); sh('wm dismiss-keyguard')
        sh(f'am start -f 0x30000000 -n {PKG}/.MainActivity --es simulate /data/user/0/{PKG}/files/test.wav')
        expected = 2 if a.overlap else 1
        while time.time() - t0 < 6 * 3600:
            time.sleep(2 if a.overlap and not overlap_started else 10)
            temp = re.search(r'temperature: (\d+)', sh('dumpsys battery'))
            mem = re.search(r'TOTAL PSS:\s+(\d+)', sh(f'dumpsys meminfo {PKG}')) or re.search(r'TOTAL\s+(\d+)', sh(f'dumpsys meminfo {PKG}'))
            samples.append({'t': round(time.time() - t0), 'temp_c': int(temp.group(1)) / 10 if temp else None, 'pss_mb': int(mem.group(1)) // 1024 if mem else None})
            if shots < 3 and time.time() - t0 > 60 + shots * 60:
                with open(OUT / f'{tag}-screen{shots}.png', 'wb') as f:
                    f.write(subprocess.run([ADB, 'exec-out', 'screencap', '-p'], capture_output=True).stdout)
                shots += 1
            new = sorted(sessions() - before, key=lambda sid: run_as(f'cat files/sessions/{sid}/created.txt'))
            first_saved = bool(new) and run_as(f'cat files/sessions/{new[0]}/state.txt').strip() == 'done'
            if a.overlap and not overlap_started and first_saved:
                # The first recording is saved and its summary is being written: record again immediately.
                time.sleep(2); overlap_started = time.time()
                sh(f'am start -f 0x30000000 -n {PKG}/.MainActivity --es simulate /data/user/0/{PKG}/files/test.wav')
            if len(new) >= expected and all(done(sid) for sid in new[:expected]): break
            if 'FATAL EXCEPTION' in sh('logcat -d -s AndroidRuntime:E'): break
    def pull(cmd):  # Retries while the phone is offline.
        for _ in range(90):
            out = sh(cmd, 300)
            if out.strip(): return out
            time.sleep(10)
        return ''
    # Some OnePlus builds create logcat -f's file but never write to it. The main
    # log buffer still contains our bounded benchmark lines, so fall back there.
    log_lines = sh('cat /data/local/tmp/ln/perf.log').splitlines()
    if not log_lines:
        log_lines = sh('logcat -d -v time -s LocalNotesPerf:I AndroidRuntime:E').splitlines()
    device_samples = []
    for line in pull('cat /data/local/tmp/ln/sample.log').splitlines():
        t, m = re.search(r'temperature: (\d+)', line), (re.search(r'TOTAL PSS:\s+(\d+)', line) or re.search(r'TOTAL\s+(\d+)', line))
        device_samples.append({'at': line.split(' ')[0], 'temp_c': int(t.group(1)) / 10 if t else None, 'pss_mb': int(m.group(1)) // 1024 if m else None})
    samples = device_samples or samples
    sh('pkill -f sample.log; pkill -f "logcat -f"')
    (OUT / f'{tag}-perf.log').write_text('\n'.join(log_lines), encoding='utf-8')
    if a.overlap:
        overlap = {sid: {'summary': done(sid), 'files': run_as(f'ls files/sessions/{sid}').split()} for sid in new}
        print('OVERLAP', json.dumps(overlap), 'second recording started', round(overlap_started - t0), 's after start', flush=True)
    session = new[0] if new else run_as('ls -t files/sessions | head -1').strip()
    seg_raw = run_as(f'cat files/sessions/{session}/segments.json')
    summary = run_as(f'cat files/sessions/{session}/summary.md 2>/dev/null')
    notes = run_as(f'cat files/sessions/{session}/notes/section-*.md 2>/dev/null')
    (OUT / f'{tag}-segments.json').write_text(seg_raw, encoding='utf-8')
    (OUT / f'{tag}-summary.md').write_text(summary, encoding='utf-8'); (OUT / f'{tag}-notes.md').write_text(notes, encoding='utf-8')
    segs = json.loads(seg_raw) if seg_raw.strip().startswith('[') else []
    # Scoring
    import jiwer
    from whisper_normalizer.english import EnglishTextNormalizer
    norm = EnglishTextNormalizer()
    ref_file = BENCH / 'eval' / (f'{a.wav}-ref.txt' if (BENCH / 'eval' / f'{a.wav}-ref.txt').exists() else f'streams/{a.wav}.txt')
    ref = norm(ref_file.read_text(encoding='utf-8'))
    final = norm(' '.join(s['t'] for s in segs))
    def nums(key): return [float(m) for m in re.findall(rf'{key}=(-?\d+)', '\n'.join(log_lines))]
    live_lag = nums('lag_ms'); refine_behind = nums('behind_ms'); refine_took = nums('took_ms')
    res = {'segments': len(segs), 'refined': sum(1 for s in segs if s.get('r')), 'final_wer': round(jiwer.wer(ref, final or 'x') * 100, 2),
           'live_lag_ms_median': sorted(live_lag)[len(live_lag) // 2] if live_lag else None, 'live_lag_ms_max': max(live_lag) if live_lag else None,
           'refine_behind_ms_median': sorted(refine_behind)[len(refine_behind) // 2] if refine_behind else None,
           'refine_behind_ms_max': max(refine_behind) if refine_behind else None,
           'notes_sections_s': [round(float(x) / 1000, 1) for x in re.findall(r'notes section=\d+ took_ms=(\d+)', '\n'.join(log_lines))],
           'overviews_s': [round(float(x) / 1000, 1) for x in re.findall(r'overview took_ms=(\d+)', '\n'.join(log_lines))],
           'summary_job_s': [round(float(x) / 1000, 1) for x in re.findall(r'finish_ms=(\d+)', '\n'.join(log_lines))],
           'max_temp_c': max((s['temp_c'] for s in samples if s['temp_c']), default=None), 'max_pss_mb': max((s['pss_mb'] for s in samples if s['pss_mb']), default=None),
           'crash': [l for l in log_lines if 'FATAL' in l or 'AndroidRuntime' in l][:5],
           'session': session}
    if a.wav == 'meeting':
        import importlib.util
        spec = importlib.util.spec_from_file_location('pb', ROOT / 'tools/phone_bench.py'); pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)
        source = (ROOT / 'tools/summary_eval_meeting.txt').read_text(encoding='utf-8')
        res['summary_score'] = pb.score(summary, source); res['notes_score'] = pb.score(notes, source); res['combined_score'] = pb.score(notes + '\n' + summary, source)
    res['samples'] = samples
    results_file = OUT / 'e2e-results.json'
    results = json.loads(results_file.read_text()) if results_file.exists() else {}
    results[tag] = res; results_file.write_text(json.dumps(results, indent=2))
    print(json.dumps({k: v for k, v in res.items() if k != 'samples'}, indent=1))

if __name__ == '__main__':
    main()
