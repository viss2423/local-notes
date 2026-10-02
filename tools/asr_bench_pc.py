"""PC CPU benchmark of speech models on the continuous domain streams (asr_eval_build.py).

Streaming models are fed 100 ms at a time, as the app's microphone would; offline models get
Silero-VAD speech segments. Reports WER (Whisper English normalizer) and real-time factor.
Usage: python tools/asr_bench_pc.py [model-key ...] [--threads 4]
"""
from pathlib import Path
import argparse, json, subprocess, time
import numpy as np
import soundfile as sf
import jiwer
import sherpa_onnx
from whisper_normalizer.english import EnglishTextNormalizer

root = Path(__file__).resolve().parents[1]
bench = root / '.tools/bench'
sherpa = bench / 'sherpa'
streams = bench / 'eval/streams'
results_file = bench / 'asr-pc-results.json'
norm = EnglishTextNormalizer()

def first(folder: Path, *names):
    for n in names:
        if (folder / n).exists(): return str(folder / n)
    raise FileNotFoundError(f'{folder}: none of {names}')

def transducer_files(d: Path):
    return dict(encoder=first(d, 'encoder.int8.onnx', 'encoder.onnx'), decoder=first(d, 'decoder.int8.onnx', 'decoder.onnx'),
                joiner=first(d, 'joiner.int8.onnx', 'joiner.onnx'), tokens=first(d, 'tokens.txt'))

STREAMING = {
    'nemotron-560': 'sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25',
    'nemotron-160': 'sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25',
    'parakeet-unified-560': 'sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-560ms',
    'kroko': 'sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06',
}
OFFLINE = {
    'parakeet-unified': 'sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming',
    'parakeet-tdt-v2': 'sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8',
    'parakeet-110m': 'sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8',
    'moonshine-base': 'sherpa-onnx-moonshine-base-en-quantized-2026-02-27',
}

def run_streaming(key, audio, threads):
    reset = not key.endswith('-noreset')
    key = key.removesuffix('-noreset')
    files = transducer_files(sherpa / STREAMING[key])
    rec = sherpa_onnx.OnlineRecognizer.from_transducer(**files, num_threads=threads, sample_rate=16000, feature_dim=80,
        decoding_method='greedy_search', enable_endpoint_detection=True, rule1_min_trailing_silence=2.4,
        rule2_min_trailing_silence=0.8, rule3_min_utterance_length=20)
    stream = rec.create_stream()
    texts, compute, worst, step = [], 0.0, 0.0, 1600
    for start in range(0, len(audio), step):
        t = time.perf_counter()
        stream.accept_waveform(16000, audio[start:start + step])
        while rec.is_ready(stream): rec.decode_stream(stream)
        if reset and rec.is_endpoint(stream):
            texts.append(rec.get_result(stream)); rec.reset(stream)
        dt = time.perf_counter() - t; compute += dt; worst = max(worst, dt)
    t = time.perf_counter()
    stream.accept_waveform(16000, np.zeros(16000 * 2, 'float32')); stream.input_finished()
    while rec.is_ready(stream): rec.decode_stream(stream)
    texts.append(rec.get_result(stream)); compute += time.perf_counter() - t
    return ' '.join(texts), compute, worst

def offline_recognizer(key, threads):
    d = sherpa / OFFLINE[key]
    if key.startswith('moonshine'):  # Moonshine v2 ships merged ONNX-runtime (.ort) graphs.
        return sherpa_onnx.OfflineRecognizer.from_moonshine_v2(encoder=first(d, 'encoder_model.ort', 'encoder_model.onnx'),
            decoder=first(d, 'decoder_model_merged.ort', 'decoder_model_merged.onnx'), tokens=first(d, 'tokens.txt'), num_threads=threads)
    return sherpa_onnx.OfflineRecognizer.from_transducer(**transducer_files(d), num_threads=threads, model_type='nemo_transducer', decoding_method='greedy_search')

def vad_segments(audio):
    config = sherpa_onnx.VadModelConfig()
    config.silero_vad.model = str(sherpa / 'silero_vad.onnx')
    config.silero_vad.min_silence_duration = 0.3; config.silero_vad.max_speech_duration = 20
    config.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=60)
    segs = []
    for i in range(0, len(audio), 512):
        vad.accept_waveform(audio[i:i + 512])
        while not vad.empty(): segs.append(np.array(vad.front.samples, 'float32')); vad.pop()
    vad.flush()
    while not vad.empty(): segs.append(np.array(vad.front.samples, 'float32')); vad.pop()
    return segs

def run_offline(key, audio, threads):
    rec = offline_recognizer(key, threads)
    segs = vad_segments(audio)
    texts, compute, worst = [], 0.0, 0.0
    for seg in segs:
        t = time.perf_counter()
        s = rec.create_stream(); s.accept_waveform(16000, seg); rec.decode_stream(s)
        texts.append(s.result.text)
        dt = time.perf_counter() - t; compute += dt; worst = max(worst, dt)
    return ' '.join(texts), compute, worst

def run_whisper(audio_path, threads):
    cli = bench / 'whisper/Release/whisper-cli.exe'
    model = bench / 'ggml-base.en-q5_1.bin'
    t = time.perf_counter()
    out = subprocess.run([str(cli), '-m', str(model), '-f', str(audio_path), '-t', str(threads), '-l', 'en', '-nt', '-np'],
                         capture_output=True, text=True, encoding='utf-8', errors='replace')
    return ' '.join(out.stdout.split()), time.perf_counter() - t, 0.0

def main():
    ap = argparse.ArgumentParser(); ap.add_argument('models', nargs='*'); ap.add_argument('--threads', type=int, default=4)
    a = ap.parse_args()
    keys = a.models or [*STREAMING, *OFFLINE, 'whisper-base-q5']
    results = json.loads(results_file.read_text()) if results_file.exists() else {}
    for key in keys:
        base = key.removesuffix('-noreset')
        if base in STREAMING and not (sherpa / STREAMING[base]).exists() or key in OFFLINE and not (sherpa / OFFLINE[key]).exists():
            print('skip (not downloaded)', key); continue
        per = {}
        for wav in sorted(streams.glob('*.wav')):
            audio, _ = sf.read(wav, dtype='float32')
            ref = (streams / f'{wav.stem}.txt').read_text(encoding='utf-8')
            if key.removesuffix('-noreset') in STREAMING: hyp, compute, worst = run_streaming(key, audio, a.threads)
            elif key in OFFLINE: hyp, compute, worst = run_offline(key, audio, a.threads)
            else: hyp, compute, worst = run_whisper(wav, a.threads)
            r, h = norm(ref), norm(hyp)
            per[wav.stem] = {'wer': round(jiwer.wer(r, h) * 100, 2), 'rtf': round(compute / (len(audio) / 16000), 4), 'worst_step_s': round(worst, 3)}
            (bench / 'asr-pc-hyp').mkdir(exist_ok=True); (bench / 'asr-pc-hyp' / f'{key}-{wav.stem}.txt').write_text(hyp, encoding='utf-8')
            print(key, wav.stem, per[wav.stem], flush=True)
        per['mean_wer'] = round(sum(v['wer'] for v in per.values()) / len(per), 2)
        per['mean_rtf'] = round(sum(v['rtf'] for k, v in per.items() if k != 'mean_wer') / (len(per) - 1), 4)
        per['threads'] = a.threads
        results[key] = per; results_file.write_text(json.dumps(results, indent=2))
        print('==', key, 'mean WER', per['mean_wer'], 'mean RTF', per['mean_rtf'], flush=True)

if __name__ == '__main__':
    main()
