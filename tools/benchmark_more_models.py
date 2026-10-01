"""CPU comparison for optional larger models; not a OnePlus timing test."""
from pathlib import Path
import json,os,re,subprocess,time
ROOT=Path(__file__).resolve().parents[1]
BENCH=ROOT/'.tools/bench'; OUT=ROOT/'validation/asr'
engine=next((BENCH/'whisper').rglob('whisper-cli.exe'))
ref=re.findall(r"[a-z0-9]+(?:'[a-z0-9]+)?",(OUT/'reference.txt').read_text().lower())
def distance(a,b):
 p=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  c=[i]
  for j,y in enumerate(b,1):c.append(min(c[-1]+1,p[j]+1,p[j-1]+(x!=y)))
  p=c
 return p[-1]
for model in ['small.en-q5_1','large-v3-turbo-q5_0']:
 for case in ['clean','noise_15db']:
  name=f'{model}-original-{case}'
  args=[str(engine),'-m',str(BENCH/f'ggml-{model}.bin'),'-f',str(OUT/f'{case}.wav'),'-t','4','-l','en','-ng','-bs','1','-bo','5','-mc','0','-otxt','-of',str(OUT/name)]
  print('START',name,flush=True)
  start=time.perf_counter(); p=subprocess.run(args,capture_output=True,timeout=600)
  elapsed=time.perf_counter()-start
  (OUT/f'{name}.log').write_bytes(p.stderr)
  if p.returncode:raise RuntimeError(p.stderr[-1500:])
  text=(OUT/f'{name}.txt').read_text(encoding='utf-8-sig')
  tokens=re.findall(r"[a-z0-9]+(?:'[a-z0-9]+)?",text.lower())
  data={'model':model,'case':case,'seconds':round(elapsed,3),'word_error_rate':round(distance(ref,tokens)/len(ref),4),'audio_seconds':133.47,'host_cpu':os.environ.get('PROCESSOR_IDENTIFIER'),'threads':4,'command':args}
  (OUT/f'{name}.result.json').write_text(json.dumps(data,indent=2))
  print('DONE',name,data['seconds'],data['word_error_rate'],flush=True)
