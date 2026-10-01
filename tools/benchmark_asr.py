"""CPU-only, version-matched ASR comparison. PC timings are NOT phone timings."""
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import wave

APP = Path(__file__).resolve().parents[1]
BENCH = APP / '.tools/bench'
sys.path.insert(0, str(BENCH / 'python'))
import numpy as np
import pyarrow.parquet as parquet
import soundfile as sf

OUT = APP / 'validation/asr'
OUT.mkdir(parents=True, exist_ok=True)
rows = parquet.read_table(BENCH / 'librispeech.parquet').to_pylist()
clips, references, ids = [], [], []
for row in rows[:12]:
    signal, rate = sf.read(io.BytesIO(row['audio']['bytes']), dtype='float32')
    assert rate == 16000 and signal.ndim == 1
    clips += [signal, np.zeros(4000, dtype=np.float32)]
    references.append(row['text'])
    ids.append(row['id'])
audio = np.concatenate(clips)
reference = ' '.join(references)
rng = np.random.default_rng(42)
noise = rng.normal(0, np.sqrt(np.mean(audio ** 2)) / (10 ** (15 / 20)), len(audio)).astype(np.float32)
cases = {'clean': audio, 'noise_15db': np.clip(audio + noise, -1, 1)}

def write_wav(path, signal):
    with wave.open(str(path), 'wb') as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(16000)
        output.writeframes((signal * 32767).astype('<i2').tobytes())
for name, signal in cases.items(): write_wav(OUT / f'{name}.wav', signal)
write_wav(OUT / 'silence.wav', np.zeros(16000 * 35, dtype=np.float32))
(OUT / 'reference.txt').write_text(reference, encoding='utf-8')
(OUT / 'dataset.json').write_text(json.dumps({'source': 'hf-internal-testing/librispeech_asr_dummy', 'ids': ids, 'duration_s': len(audio) / 16000, 'sample_rate': 16000, 'speakers': sorted(set(row['speaker_id'] for row in rows[:12])), 'noise': 'seed42 additive Gaussian at whole-clip SNR15dB', 'limits': 'Small read-speech sample, not a meeting/accent benchmark.'}, indent=2))

def words(text): return re.findall(r"[a-z0-9]+(?:'[a-z0-9]+)?", text.lower())
def distance(a, b):
    previous = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        current = [i]
        for j, y in enumerate(b, 1): current.append(min(current[-1] + 1, previous[j] + 1, previous[j-1] + (x != y)))
        previous = current
    return previous[-1]

engine = next((BENCH / 'whisper').rglob('whisper-cli.exe'))
profiles = [('small.en', 'original', 5, False), ('base.en', 'original', 5, False),
            ('base.en-q5_1', 'original', 5, False), ('tiny.en', 'original', 5, False),
            ('base.en', 'fast', 1, True), ('base.en-q5_1', 'fast', 1, True)]
results = []
for model, profile, best_of, no_fallback in profiles:
    for case in cases:
        key = f'{model}-{profile}-{case}'
        record = OUT / f'{key}.result.json'
        if record.exists():
            results.append(json.loads(record.read_text())); continue
        prefix = OUT / key
        command = [str(engine), '-m', str(BENCH / f'ggml-{model}.bin'), '-f', str(OUT / f'{case}.wav'),
                   '-t', '4', '-l', 'en', '-ng', '-bs', '1', '-bo', str(best_of), '-mc', '0', '-otxt', '-of', str(prefix)]
        if no_fallback: command.append('-nf')
        print('START', key, flush=True)
        start = time.perf_counter()
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
        elapsed = time.perf_counter() - start
        prefix.with_suffix(prefix.suffix + '.log').write_bytes(result.stderr)
        if result.returncode: raise RuntimeError(f'{key} failed: {result.stderr[-1500:]}')
        transcript = Path(str(prefix) + '.txt').read_text(encoding='utf-8-sig')
        error = distance(words(reference), words(transcript)) / len(words(reference))
        data = {'model': model, 'profile': profile, 'case': case, 'seconds': round(elapsed, 3),
                'audio_seconds': len(audio) / 16000, 'realtime_factor': round(elapsed / (len(audio) / 16000), 4),
                'word_error_rate': round(error, 4), 'reference_words': len(words(reference)), 'command': command,
                'host_cpu': os.environ.get('PROCESSOR_IDENTIFIER'), 'threads': 4, 'gpu': False}
        record.write_text(json.dumps(data, indent=2))
        results.append(data)
        (OUT / 'results.json').write_text(json.dumps(results, indent=2))
        print('DONE', key, f'{elapsed:.1f}s WER={error:.1%}', flush=True)
(OUT / 'results.json').write_text(json.dumps(results, indent=2))
