"""Prepare a repeatable 3h13m background-recording fixture from endurance.wav.

The output stays in .tools/bench/eval (ignored by Git). It is synthetic and is
intended for Android capture, timing, heat, storage and processing endurance.
"""
from pathlib import Path
import wave

root = Path(__file__).resolve().parents[1] / '.tools/bench/eval'
source = root / 'endurance.wav'
target = root / 'endurance3h.wav'
with wave.open(str(source), 'rb') as source_wav, wave.open(str(target), 'wb') as target_wav:
    assert source_wav.getnchannels() == 1 and source_wav.getsampwidth() == 2 and source_wav.getframerate() == 16000
    target_wav.setparams(source_wav.getparams())
    for repeat in range(8):
        source_wav.rewind()
        while block := source_wav.readframes(16000 * 10):
            target_wav.writeframesraw(block)
        print('written', repeat + 1, 'of 8', flush=True)
reference = (root / 'endurance-ref.txt').read_text(encoding='utf-8').strip()
(root / 'endurance3h-ref.txt').write_text(' '.join([reference] * 8), encoding='utf-8')
with wave.open(str(target), 'rb') as wav:
    print('duration_s', round(wav.getnframes() / wav.getframerate(), 2), 'bytes', target.stat().st_size)
