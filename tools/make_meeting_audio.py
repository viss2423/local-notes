"""Synthesizes tools/summary_eval_meeting.txt as a multi-voice meeting recording (16 kHz mono WAV).

Lines rotate through four VCTK speakers with natural gaps, giving an end-to-end test where the
transcript and the summary facts are both known. Output: .tools/bench/eval/meeting.wav (+ meeting-ref.txt).
"""
from pathlib import Path
import re
import numpy as np
import sherpa_onnx
import soundfile as sf

root = Path(__file__).resolve().parents[1]
model = root / '.tools/bench/sherpa/vits-piper-en_GB-vctk-medium'
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(model / 'en_GB-vctk-medium.onnx'), tokens=str(model / 'tokens.txt'),
                                               data_dir=str(model / 'espeak-ng-data')), num_threads=4)))
voices = [4, 17, 33, 61]  # four distinct speakers
pieces, refs = [], []
lines = (root / 'tools/summary_eval_meeting.txt').read_text(encoding='utf-8').strip().splitlines()
for i, line in enumerate(lines):
    text = re.sub(r'^\[[\d:]+\]\s*', '', line)
    audio = tts.generate(text, sid=voices[i % len(voices)], speed=1.05)
    samples = np.array(audio.samples, dtype='float32')
    if audio.sample_rate != 16000:
        n = int(len(samples) * 16000 / audio.sample_rate)
        samples = np.interp(np.linspace(0, len(samples) - 1, n), np.arange(len(samples)), samples).astype('float32')
    pieces += [samples * 0.8, np.zeros(int(16000 * (0.5 + 0.4 * (i % 3))), 'float32')]
    refs.append(text)
out = root / '.tools/bench/eval'
audio = np.concatenate(pieces)
sf.write(out / 'meeting.wav', audio, 16000, subtype='PCM_16')
(out / 'meeting-ref.txt').write_text(' '.join(refs), encoding='utf-8')
print('meeting.wav', round(len(audio) / 16000, 1), 's,', len(lines), 'turns')
