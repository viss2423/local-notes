"""Benchmarks speech and summary models ON THE PHONE over adb (CPU, the phone's own cores).

Speech: sherpa-onnx command-line tools on the domain streams from asr_eval_build.py; accuracy is scored
on the PC with the same normalizer as asr_bench_pc.py. Summary: llama.cpp (official Android build) runs
the app's own pipeline (section notes, then an overview) on tools/summary_eval_meeting.txt and is scored
against tools/summary_eval_facts.json.
Usage: python tools/phone_bench.py asr [model ...] | llm [model ...] | push
"""
from pathlib import Path
import json, re, subprocess, sys, time

ROOT = Path(__file__).resolve().parents[1]
BENCH = ROOT / '.tools/bench'
ADB = str(ROOT / '.tools/sdk/platform-tools/adb.exe')
DEV = '/data/local/tmp/ln'
OUT = BENCH / 'phone'; OUT.mkdir(exist_ok=True)

def adb(*args, check=True, timeout=7200):
    r = subprocess.run([ADB, *args], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
    if check and r.returncode: raise RuntimeError(f'adb {args[:2]} failed: {r.stderr[-800:]}')
    return r

def shell(cmd, timeout=7200):
    t = time.perf_counter(); r = adb('shell', cmd, check=False, timeout=timeout); return r, time.perf_counter() - t

def push(local: Path, remote: str):
    if shell(f'[ -e {remote} ] && echo yes')[0].stdout.strip() != 'yes':
        print('push', local.name, flush=True); adb('push', str(local), remote, timeout=7200)

STREAMING = {
    'kroko': ('sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06', ''),
    'nemotron-560': ('sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25', '.int8'),
    'nemotron-160': ('sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25', '.int8'),
    'parakeet-unified-560': ('sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-560ms', '.int8'),
}
OFFLINE = {
    'parakeet-unified': 'sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming',
    'parakeet-tdt-v2': 'sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8',
    'parakeet-110m': 'sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8',
}

def prepare_base():
    shell(f'mkdir -p {DEV}/bin {DEV}/streams {DEV}/models {DEV}/llm')
    for f in (BENCH / 'sherpa/sherpa-onnx-v1.13.8-android-aarch64-termux-static/bin').iterdir():
        if f.name in ('sherpa-onnx', 'sherpa-onnx-offline', 'sherpa-onnx-vad-with-offline-asr'): push(f, f'{DEV}/bin/{f.name}')
    # The termux build links the shared C++ runtime; Android does not ship it, so take it from the NDK.
    push(next((ROOT / '.tools/sdk/ndk').glob('*/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so')), f'{DEV}/bin/libc++_shared.so')
    shell(f'chmod 755 {DEV}/bin/*')
    for f in (BENCH / 'eval/streams').glob('*.wav'): push(f, f'{DEV}/streams/{f.name}')
    push(BENCH / 'sherpa/silero_vad.onnx', f'{DEV}/models/silero_vad.onnx')

def model_files(folder: str, suffix: str):
    d = BENCH / 'sherpa' / folder
    names = [f'encoder{suffix}.onnx', f'decoder{suffix}.onnx', f'joiner{suffix}.onnx', 'tokens.txt']
    names = [n if (d / n).exists() else n.replace('.int8', '') for n in names]
    shell(f'mkdir -p {DEV}/models/{folder}')
    for n in names: push(d / n, f'{DEV}/models/{folder}/{n}')
    m = f'{DEV}/models/{folder}'
    return f'--encoder={m}/{names[0]} --decoder={m}/{names[1]} --joiner={m}/{names[2]} --tokens={m}/{names[3]}'

def text_of(stdout: str) -> str:
    # The tools print a JSON-ish result line per file: {"text": "...", ...} or plain "text" lines.
    texts = re.findall(r'"text"\s*:\s*"((?:[^"\\]|\\.)*)"', stdout)
    return ' '.join(json.loads(f'"{t}"') for t in texts)

def asr(models, threads=2):
    import jiwer
    from whisper_normalizer.english import EnglishTextNormalizer
    norm = EnglishTextNormalizer()
    prepare_base()
    results_file = OUT / 'asr-phone-results.json'
    results = json.loads(results_file.read_text()) if results_file.exists() else {}
    for key in models or [*STREAMING, *OFFLINE]:
        per = {}
        for wav in sorted((BENCH / 'eval/streams').glob('*.wav')):
            seconds = json.loads((BENCH / 'eval/streams/manifest.json').read_text())[wav.stem]['seconds']
            if key in STREAMING:
                folder, suffix = STREAMING[key]
                cmd = f'cd {DEV} && LD_LIBRARY_PATH=bin ./bin/sherpa-onnx {model_files(folder, suffix)} --num-threads={threads} --provider=cpu streams/{wav.name}'
            else:
                cmd = (f'cd {DEV} && LD_LIBRARY_PATH=bin ./bin/sherpa-onnx-vad-with-offline-asr --silero-vad-model=models/silero_vad.onnx '
                       f'{model_files(OFFLINE[key], ".int8")} --model-type=nemo_transducer --num-threads={threads} streams/{wav.name}')
            r, wall = shell(cmd)
            (OUT / f'{key}-{wav.stem}.log').write_text(r.stdout + '\n----\n' + r.stderr, encoding='utf-8')
            hyp = text_of(r.stdout + r.stderr)
            if not hyp:  # vad-with-offline-asr prints "start -- end text" lines
                hyp = ' '.join(m.group(1) for m in re.finditer(r'^\s*[\d.]+\s*--\s*[\d.]+\s*(.*)$', r.stdout + r.stderr, re.M))
            ref = (BENCH / f'eval/streams/{wav.stem}.txt').read_text(encoding='utf-8')
            per[wav.stem] = {'wer': round(jiwer.wer(norm(ref), norm(hyp) or 'x') * 100, 2), 'rtf': round(wall / seconds, 4)}
            print(key, wav.stem, per[wav.stem], flush=True)
        per['mean_wer'] = round(sum(v['wer'] for v in per.values()) / len(per), 2)
        per['mean_rtf'] = round(sum(v['rtf'] for k, v in per.items() if k != 'mean_wer') / (len(per) - 1), 4)
        per['threads'] = threads
        results[f'{key}@{threads}t'] = per; results_file.write_text(json.dumps(results, indent=2))
        print('==', key, threads, 'threads: WER', per['mean_wer'], 'RTF', per['mean_rtf'], flush=True)

# ---------------- summaries ----------------
LLMS = {
    'qwen35-2b': ('Qwen3.5-2B-Q4_0.gguf', 'chatml'),
    'qwen35-0.8b': ('Qwen3.5-0.8B-Q4_0.gguf', 'chatml'),
    'gemma4-e2b': ('gemma-4-E2B_q4_0-it.gguf', 'gemma'),
    'qwen25-1.5b-old': ('../qwen-summary.gguf', 'chatml-plain'),
}
def kotlin_prompts():
    """Reads the system/section/overview prompts from the app source so the benchmark tests what ships."""
    src = (ROOT / 'app/src/main/java/dev/localnotes/Summaries.kt').read_text(encoding='utf-8')
    def const(name):
        body = re.search(rf'(?:const val|fun) {name}[^=]*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', src).group(1)
        return ''.join(json.loads(f'"{s}"') for s in re.findall(r'"((?:[^"\\]|\\.)*)"', body)).replace('$transcript', '{x}').replace('$notes', '{x}')
    return const('system'), const('section'), const('overview')

def fmt(template, system, user, start=''):
    if template == 'gemma': return f'<|turn>system\n{system}<turn|>\n<|turn>user\n{user}<turn|>\n<|turn>model\n' + start
    think = '' if template == 'chatml-plain' else '<think>\n\n</think>\n\n'
    return f'<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n{think}' + start

def run_llm(name, prompt_text, threads, max_tokens, variant, sampling=''):
    local = OUT / 'prompt.txt'; local.write_text(prompt_text, encoding='utf-8', newline='\n')  # LF only: CRLF breaks chat markers
    adb('push', str(local), f'{DEV}/llm/prompt.txt')
    cmd = (f'cd {DEV}/llm/{variant} && LD_LIBRARY_PATH=. ./llama-completion -m ../{name} -f ../prompt.txt -n {max_tokens} '
           f'-t {threads} -tb {threads} -c 8192 -b 512 {sampling or "--temp 0"} -no-cnv --no-display-prompt --no-warmup 2>/data/local/tmp/ln/llm/err.txt; '
           f'echo; echo ===STDERR===; cat /data/local/tmp/ln/llm/err.txt')
    r, wall = shell(cmd)
    out, err = (r.stdout.split('===STDERR===') + [''])[:2]
    pp = re.search(r'prompt eval time =\s*([\d.]+) ms /\s*(\d+) tokens', err)
    tg = re.search(r'\beval time =\s*([\d.]+) ms /\s*(\d+) runs', err)
    stats = {'wall_s': round(wall, 2),
             'prompt_tok_s': round(int(pp.group(2)) / float(pp.group(1)) * 1000, 1) if pp and float(pp.group(1)) > 0 else None,
             'gen_tok_s': round(int(tg.group(2)) / float(tg.group(1)) * 1000, 1) if tg and float(tg.group(1)) > 0 else None,
             'gen_tokens': int(tg.group(2)) if tg else 0}
    text = re.sub(r'(?s)<think>.*?</think>', '', out).replace('[end of text]', '').strip()
    return text, stats, err

def score(text, source):
    spec = json.loads((ROOT / 'tools/summary_eval_facts.json').read_text())
    low = text.lower()
    passed = [f['id'] for f in spec['facts'] if all(any(re.search(p, low) for p in group) for group in f['all'])]
    traps = [t['id'] for t in spec['traps'] if re.search(t['re'], low)]
    # Numbers the source states (digits or words, incl. compounds like "forty nine" / "twelve thousand five hundred").
    units = {'one':1,'two':2,'three':3,'four':4,'five':5,'six':6,'seven':7,'eight':8,'nine':9,'ten':10,'eleven':11,'twelve':12,
             'thirteen':13,'fourteen':14,'fifteen':15,'sixteen':16,'seventeen':17,'eighteen':18,'nineteen':19,'first':1,'seventh':7,'fourteenth':14,'twentieth':20}
    tens = {'twenty':20,'thirty':30,'forty':40,'fifty':50,'sixty':60,'seventy':70,'eighty':80,'ninety':90}
    allowed = set(re.findall(r'\d+', source.replace(',', '')))
    words = re.findall(r'[a-z]+', source.lower())
    for i, w in enumerate(words):
        if w in units: allowed.add(str(units[w]))
        if w in tens:
            allowed.add(str(tens[w]))
            if i + 1 < len(words) and words[i + 1] in units: allowed.add(str(tens[w] + units[words[i + 1]]))
    allowed |= {'12500', '4200', '8300', '900', '2026', '2025'}
    invented = sorted({n for n in re.findall(r'\d+', low.replace(',', '')) if n not in allowed})
    return {'facts': len(passed), 'of': len(spec['facts']), 'missed': [f['id'] for f in spec['facts'] if f['id'] not in passed],
            'traps': traps, 'invented_numbers': invented}

FINAL = '--temp 0.2 --top-k 20 --top-p 0.9 --repeat-penalty 1.05 --repeat-last-n 256 --dry-multiplier 0.8 --dry-base 1.75 --dry-allowed-length 2 --dry-penalty-last-n 512'
SAMPLING = {
    'final1': FINAL + ' --seed 1', 'final2': FINAL + ' --seed 2', 'final3': FINAL + ' --seed 3',
    'greedy': '--temp 0 --repeat-penalty 1.0',
    'greedy-pen': '--temp 0 --repeat-penalty 1.05 --repeat-last-n 256 --dry-multiplier 0.8 --dry-base 1.75 --dry-allowed-length 2 --dry-penalty-last-n 512',
    'warm-pen': '--temp 0.3 --top-k 20 --top-p 0.8 --seed 7 --repeat-penalty 1.1 --repeat-last-n 256 --presence-penalty 0.5 --dry-multiplier 0.8 --dry-base 1.75 --dry-allowed-length 2 --dry-penalty-last-n 512',
}

def max_repeats(text):
    lines = [re.sub(r'\W+', ' ', l).strip().lower() for l in text.splitlines() if l.strip()]
    return max((lines.count(l) for l in set(lines)), default=0)

def llm(models, threads=4, variant='cpu', source='script', sampling='greedy'):
    shell(f'mkdir -p {DEV}/llm')
    pkg = {'cpu': 'llama-b11320-bin-android-arm64.tar.gz', 'snap': 'llama-b11320-bin-android-arm64-snapdragon.tar.gz'}[variant]
    if shell(f'[ -d {DEV}/llm/{variant} ] && echo yes')[0].stdout.strip() != 'yes':
        folder = BENCH / 'llm' / ('android' if variant == 'cpu' else 'snap') / 'llama-b11320'
        adb('push', str(folder), f'{DEV}/llm/{variant}'); shell(f'chmod 755 {DEV}/llm/{variant}/*')
    system, section, overview = kotlin_prompts()
    meeting = (ROOT / 'tools/summary_eval_meeting.txt').read_text(encoding='utf-8')
    if source == 'e2e':  # The speech-recognized transcript from the on-phone end-to-end run (with its real errors).
        segs = json.loads(next((BENCH / 'e2e').glob('*meeting-segments.json')).read_text(encoding='utf-8'))
        asr_lines = [s['t'] for s in segs if s['t'].strip()]
    # Same sectioning as NotesBuilder: ~420 words of transcript text per section, timestamps removed.
    lines = asr_lines if source == 'e2e' else [re.sub(r'^\[[\d:]+\]\s*', '', l) for l in meeting.strip().splitlines()]
    sections, cur, words = [], [], 0
    for l in lines:
        cur.append(l); words += len(l.split())
        if words >= 500: sections.append('\n'.join(cur)); cur, words = [], 0
    if cur: sections.append('\n'.join(cur))
    results_file = OUT / 'llm-phone-results.json'
    results = json.loads(results_file.read_text()) if results_file.exists() else {}
    for key in models or list(LLMS):
        name, template = LLMS[key]
        local = BENCH / 'llm' / name if not name.startswith('..') else BENCH / name[3:]
        push(local, f'{DEV}/llm/{Path(name).name}')
        name = Path(name).name
        notes, stats = [], []
        for s in sections:
            text, st, err = run_llm(name, fmt(template, system, section.replace('{x}', s), '- '), threads, 650, variant, SAMPLING[sampling])
            text = '- ' + text  # Prefill is part of the reply, as in the app.
            print('section call', st, repr(text[:200]), flush=True)
            if not text: print(err[-1500:])
            notes.append(text); stats.append(st)
        text, st, err = run_llm(name, fmt(template, system, overview.replace('{x}', '\n'.join(notes)), '## In short\n'), threads, 500, variant, SAMPLING[sampling])
        text = '## In short\n' + text
        print('overview call', st, repr(text[:300]), flush=True)
        if not text: print(err[-2500:], flush=True)
        stats.append(st)
        detailed, concise = '\n'.join(notes), text
        key_tag = f'{key}-{source}-{sampling}'
        (OUT / f'{key_tag}-{variant}-{threads}t-notes.md').write_text(detailed, encoding='utf-8')
        (OUT / f'{key_tag}-{variant}-{threads}t-summary.md').write_text(concise, encoding='utf-8')
        res = {'sections': len(sections), 'total_s': round(sum(s['wall_s'] for s in stats), 1), 'calls': stats,
               'notes_score': score(detailed, meeting), 'summary_score': score(concise, meeting), 'combined_score': score(detailed + '\n' + concise, meeting), 'max_repeat': max_repeats(detailed + '\n' + concise)}
        results[f'{key_tag}@{variant}-{threads}t'] = res; results_file.write_text(json.dumps(results, indent=2))
        print('==', key, variant, threads, 't: total', res['total_s'], 's | notes facts', res['notes_score']['facts'], '/', res['notes_score']['of'],
              '| summary facts', res['summary_score']['facts'], '| traps', res['combined_score']['traps'], '| invented', res['combined_score']['invented_numbers'], '| max repeat', res['max_repeat'],
              '| pp tok/s', [s['prompt_tok_s'] for s in stats], '| gen tok/s', [s['gen_tok_s'] for s in stats], flush=True)

if __name__ == '__main__':
    mode, *rest = sys.argv[1:] or ['asr']
    threads = next((int(a.split('=')[1]) for a in rest if a.startswith('--threads=')), None)
    variant = next((a.split('=')[1] for a in rest if a.startswith('--variant=')), 'cpu')
    names = [a for a in rest if not a.startswith('--')]
    if mode == 'asr': asr(names, threads or 2)
    elif mode == 'llm':
        source = next((a.split('=')[1] for a in rest if a.startswith('--source=')), 'script')
        sampling = next((a.split('=')[1] for a in rest if a.startswith('--sampling=')), 'greedy')
        llm(names, threads or 4, variant, source, sampling)
    elif mode == 'push': prepare_base()
