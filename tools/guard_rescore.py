"""Re-scores saved summary trials with the app's NumberGuard (Python port of Summaries.kt) applied."""
from pathlib import Path
import json, re, sys
sys.path.insert(0, str(Path(__file__).parent))
import phone_bench as pb

UNITS = dict(zero=0, one=1, two=2, three=3, four=4, five=5, six=6, seven=7, eight=8, nine=9, ten=10, eleven=11, twelve=12, thirteen=13,
             fourteen=14, fifteen=15, sixteen=16, seventeen=17, eighteen=18, nineteen=19, first=1, second=2, third=3, fourth=4, fifth=5,
             sixth=6, seventh=7, eighth=8, ninth=9, tenth=10, fourteenth=14, twentieth=20)
TENS = dict(twenty=20, thirty=30, forty=40, fifty=50, sixty=60, seventy=70, eighty=80, ninety=90)

def numbers(text):
    out = set()
    for v in re.findall(r'\d+(?:[.,]\d+)*', text):
        v = v.replace(',', ''); out.add(v); out.update(v.split('.'))
    words = re.findall(r'[a-z]+', text.lower()); i = 0
    while i < len(words):
        value, current, j = -1, 0, i
        while j < len(words):
            w = words[j]
            if w in UNITS: current += UNITS[w]
            elif w in TENS: current += TENS[w]
            elif w == 'hundred' and (current > 0 or value >= 0): current = max(current, 1) * 100
            elif w == 'thousand' and (current > 0 or value >= 0): value = max(value, 0) + max(current, 1) * 1000; current = 0
            elif w == 'and' and j > i: pass
            else: break
            if w in UNITS or w in TENS: out.add(str(max(value, 0) + current))
            j += 1
        if j > i: out.add(str(max(value, 0) + current))
        i = max(j, i + 1)
    return out

def guard(text, source):
    allowed = numbers(source)
    kept, dropped = [], []
    for line in text.splitlines():
        (kept if line.lstrip().startswith('#') or numbers(re.sub(r'^\s*[-*•]?\s*\d{1,2}[.)]\s+', '', line)) <= allowed else dropped).append(line)
    return '\n'.join(kept), dropped

if __name__ == '__main__':
    out = pb.OUT
    script = (pb.ROOT / 'tools/summary_eval_meeting.txt').read_text(encoding='utf-8')
    segs = json.loads(next((pb.BENCH / 'e2e').glob('*meeting-segments.json')).read_text(encoding='utf-8'))
    e2e = ' '.join(s['t'] for s in segs)
    rows = {}
    for notes_file in sorted(out.glob('*-final[123]-cpu-4t-notes.md')):
        name = notes_file.name.replace('-cpu-4t-notes.md', '')
        src = e2e if '-e2e-' in name else re.sub(r'\[[\d:]+\]', '', script)
        notes = notes_file.read_text(encoding='utf-8'); summary = (out / notes_file.name.replace('notes', 'summary')).read_text(encoding='utf-8')
        g_notes, d1 = guard(notes, src); g_sum, d2 = guard(summary, g_notes)
        before, after = pb.score(notes + '\n' + summary, script), pb.score(g_notes + '\n' + g_sum, script)
        rows[name] = (before['facts'], after['facts'], len(before['invented_numbers']), len(after['invented_numbers']), len(d1) + len(d2), before['traps'], after['traps'])
        print(f"{name:34s} facts {before['facts']:2d} -> {after['facts']:2d} | invented {before['invented_numbers']} -> {after['invented_numbers']} | dropped {len(d1)+len(d2)} | traps {after['traps']}")
        for d in d1 + d2: print('      dropped:', d.strip()[:110])
