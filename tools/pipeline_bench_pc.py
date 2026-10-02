"""Accuracy of the app's actual transcription pipeline, emulated on the PC.

Live model streams 100 ms chunks with the app's endpoint rules and resets (LiveRecognizer); every finished
sentence spans all audio since the previous one (as in Speech.kt) and is re-transcribed by the accuracy
model (Refiner). Reports live WER and final WER per domain.
Usage: python tools/pipeline_bench_pc.py live=kroko,nemotron-560 refine=parakeet-unified,parakeet-tdt-v2,none
"""
from pathlib import Path
import json, sys, time
import numpy as np, soundfile as sf, jiwer, sherpa_onnx
from whisper_normalizer.english import EnglishTextNormalizer
sys.path.insert(0, str(Path(__file__).parent))
from asr_bench_pc import STREAMING, OFFLINE, transducer_files, sherpa, streams, bench

norm = EnglishTextNormalizer()
args = dict(a.split('=') for a in sys.argv[1:])
lives = args.get('live', 'kroko,nemotron-560').split(',')
refines = args.get('refine', 'parakeet-unified,parakeet-tdt-v2,none').split(',')
groups = [int(g) for g in args.get('group', '0').split(',')]  # seconds of speech per accuracy-pass chunk; 0 = per sentence

def live_segments(key, audio):
    rec = sherpa_onnx.OnlineRecognizer.from_transducer(**transducer_files(sherpa / STREAMING[key]), num_threads=4, sample_rate=16000,
        feature_dim=80, decoding_method='greedy_search', enable_endpoint_detection=True,
        rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8, rule3_min_utterance_length=20)
    s = rec.create_stream(); segs = []; start = 0; fed = 0
    def finish(r):
        if r.text.strip():
            first = start + int((r.timestamps[0] if r.timestamps else 0) * 16000)
            segs.append((max(start, first - 40000), max(fed, start + 4800), r.text.strip()))
    for i in range(0, len(audio), 1600):
        s.accept_waveform(16000, audio[i:i + 1600]); fed += len(audio[i:i + 1600])
        while rec.is_ready(s): rec.decode_stream(s)
        if rec.is_endpoint(s):
            finish(rec.get_result_all(s)); rec.reset(s); start = fed
    s.accept_waveform(16000, np.zeros(16000, 'float32')); s.input_finished()
    while rec.is_ready(s): rec.decode_stream(s)
    finish(rec.get_result_all(s))
    return segs

refiners = {}
def refiner(key):
    if key not in refiners:
        refiners[key] = sherpa_onnx.OfflineRecognizer.from_transducer(**transducer_files(sherpa / OFFLINE[key]), num_threads=4,
            model_type='nemo_transducer', decoding_method='greedy_search')
    return refiners[key]

results_file = bench / ('pipeline-pc-results.json' if groups == [0] else 'pipeline-group-results.json')
results = json.loads(results_file.read_text()) if results_file.exists() else {}
for live in lives:
    per_domain = {}
    for wav in sorted(streams.glob('*.wav')):
        audio, _ = sf.read(wav, dtype='float32')
        ref = norm((streams / f'{wav.stem}.txt').read_text(encoding='utf-8'))
        segs = live_segments(live, audio)
        row = {'segments': len(segs), 'mean_seg_s': round(float(np.mean([(b - a) / 16000 for a, b, _ in segs])), 1) if segs else 0,
               'live_wer': round(jiwer.wer(ref, norm(' '.join(t for _, _, t in segs))) * 100, 2)}
        for rk in refines:
            if rk == 'none': continue
            rec = refiner(rk)
            for g in groups:
                # Group consecutive sentences until they span >= g seconds (as the app's paragraph re-check would).
                chunks, cur = [], []
                for seg in segs:
                    cur.append(seg)
                    if (cur[-1][1] - cur[0][0]) / 16000 >= g: chunks.append(cur); cur = []
                if cur: chunks.append(cur)
                texts = []; t0 = time.perf_counter()
                for chunk in chunks:
                    a0, b0 = chunk[0][0], chunk[-1][1]
                    st = rec.create_stream(); st.accept_waveform(16000, audio[max(0, a0 - 3200):b0 + 3200]); rec.decode_stream(st)
                    texts.append(st.result.text.strip() or ' '.join(t for _, _, t in chunk))
                suffix = '' if g == 0 else f'@{g}s'
                row[f'{rk}{suffix}_wer'] = round(jiwer.wer(ref, norm(' '.join(texts))) * 100, 2)
                row[f'{rk}{suffix}_rtf'] = round((time.perf_counter() - t0) / (len(audio) / 16000), 4)
        per_domain[wav.stem] = row
        print(live, wav.stem, row, flush=True)
    keys = [k for k in next(iter(per_domain.values())) if k.endswith('_wer')]
    mean = {k: round(sum(r[k] for r in per_domain.values()) / len(per_domain), 2) for k in keys}
    results[live] = {'domains': per_domain, 'mean': mean}
    results_file.write_text(json.dumps(results, indent=2))
    print('==', live, mean, flush=True)
