"""Score phone speaker IDs against the synthetic four-voice meeting's known turns.

Usage: python tools/score_speakers.py path/to/segments.json
The fixture concatenates 44 turns in repeating voice order with >=0.5 s silent gaps.
"""
from pathlib import Path
from itertools import combinations
import json
import sys

import numpy as np
import soundfile as sf


def turns(audio, rate):
    quiet = np.abs(audio) < 1e-8
    edges = np.diff(np.r_[False, quiet, False].astype('int8'))
    start = 0
    found = []
    for a, b in zip(np.where(edges == 1)[0], np.where(edges == -1)[0]):
        if b - a < rate * .35:
            continue
        if a - start >= rate * .7:
            found.append((start * 1000 / rate, a * 1000 / rate))
        start = b
    if len(audio) - start >= rate * .7:
        found.append((start * 1000 / rate, len(audio) * 1000 / rate))
    assert len(found) == 44, len(found)
    return found


def score(path):
    root = Path(__file__).resolve().parents[1]
    audio, rate = sf.read(root / '.tools/bench/eval/meeting.wav', dtype='float32')
    segments = json.loads(Path(path).read_text(encoding='utf-8'))
    assigned = []
    for start, end in turns(audio, rate):
        overlaps = [max(0, min(end, s['e']) - max(start, s['s'])) for s in segments]
        best = max(range(len(overlaps)), key=overlaps.__getitem__)
        assigned.append(segments[best].get('speaker') if overlaps[best] else None)
    same_true = same_pred = correct_same = correct = 0
    for i, j in combinations(range(len(assigned)), 2):
        truth = i % 4 == j % 4
        pred = assigned[i] is not None and assigned[i] == assigned[j]
        same_true += truth
        same_pred += pred
        correct_same += truth and pred
        correct += truth == pred
    result = {
        'segments': len(segments),
        'detected_speakers': sorted({x for x in assigned if x is not None}),
        'unassigned_turns': assigned.count(None),
        'pairwise_correct': round(correct / 946, 3),
        'same_voice_precision': round(correct_same / same_pred, 3) if same_pred else None,
        'same_voice_recall': round(correct_same / same_true, 3),
        'assigned_by_turn': assigned,
    }
    return result


if __name__ == '__main__':
    print(json.dumps(score(sys.argv[1]), indent=2))
