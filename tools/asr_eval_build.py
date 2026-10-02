"""Builds continuous ~4-minute test streams per domain from Open ASR Leaderboard shards.

Each domain: random (seeded) utterances of 2-20 s, joined with 0.4 s silence into one 16 kHz WAV,
plus the joined reference text. Inputs: .tools/bench/eval/*.parquet. Outputs: .tools/bench/eval/streams/.
"""
from pathlib import Path
import io, json, random
import numpy as np
import pyarrow.parquet as pq
import soundfile as sf

root = Path(__file__).resolve().parents[1] / '.tools/bench/eval'
out = root / 'streams'; out.mkdir(exist_ok=True)
TARGET = 240.0
manifest = {}
for shard in sorted(root.glob('*.parquet')):
    domain = shard.name.split('_test')[0]
    table = pq.read_table(shard)
    cols = table.column_names
    text_col = next(c for c in ('text', 'normalized_text', 'transcription') if c in cols)
    rows = list(range(table.num_rows))
    random.Random(2026).shuffle(rows)
    audio_col = table.column('audio')
    texts = table.column(text_col)
    pieces, refs, total = [], [], 0.0
    for i in rows:
        item = audio_col[i].as_py()
        data, rate = sf.read(io.BytesIO(item['bytes']), dtype='float32')
        if data.ndim > 1: data = data.mean(axis=1)
        if rate != 16000:
            n = int(len(data) * 16000 / rate)
            data = np.interp(np.linspace(0, len(data) - 1, n), np.arange(len(data)), data).astype('float32')
        seconds = len(data) / 16000
        ref = str(texts[i].as_py()).strip()
        if not (2.0 <= seconds <= 20.0) or not ref or ref.lower() in ('ignore_time_segment_in_scoring',):
            continue
        pieces += [data, np.zeros(int(0.4 * 16000), 'float32')]
        refs.append(ref); total += seconds + 0.4
        if total >= TARGET: break
    audio = np.concatenate(pieces)
    sf.write(out / f'{domain}.wav', audio, 16000, subtype='PCM_16')
    (out / f'{domain}.txt').write_text(' '.join(refs), encoding='utf-8')
    manifest[domain] = {'seconds': round(len(audio) / 16000, 2), 'utterances': len(refs), 'words': sum(len(r.split()) for r in refs), 'source': shard.name}
    print(domain, manifest[domain])
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2))
