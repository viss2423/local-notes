"""Evaluate official speaker models on four known voices; PC-only fixture."""
from pathlib import Path
from itertools import permutations
import json,time,sys
import numpy as np, soundfile as sf, sherpa_onnx
root=Path(__file__).resolve().parents[1]
model=root/'.tools/bench/diarization/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx'
audio,rate=sf.read(root/'.tools/bench/eval/meeting.wav',dtype='float32')
assert rate==16000
# Synthesized turns were concatenated with 0.5/0.9/1.3-second exact-zero gaps.
zeros=np.abs(audio)<1e-8
edges=np.diff(np.r_[False,zeros,False].astype('int8'))
runs=[(a,b) for a,b in zip(np.where(edges==1)[0],np.where(edges==-1)[0]) if b-a>=rate*.35]
spans=[]; start=0
for a,b in runs:
 if a-start>=rate*.7:spans.append((start,a))
 start=b
if len(audio)-start>=rate*.7:spans.append((start,len(audio)))
print('turns',len(spans),'durations',[(round((b-a)/rate,1)) for a,b in spans[:8]],flush=True)
assert len(spans)==44, 'Fixture turn detection changed'
crop_seconds=float(sys.argv[1]) if len(sys.argv)>1 else 0
if crop_seconds:
 spans=[(a,min(b,a+int(crop_seconds*rate))) for a,b in spans]
 print('crop seconds',crop_seconds,flush=True)
e=sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(model),num_threads=1))
vectors=[];times=[]
for i,(a,b) in enumerate(spans):
 stream=e.create_stream();stream.accept_waveform(rate,audio[a:b]);stream.input_finished()
 t=time.perf_counter(); v=np.asarray(e.compute(stream),dtype='float32');times.append(time.perf_counter()-t)
 v/=np.linalg.norm(v)+1e-12;vectors.append(v)
 if i%10==0:print('embedding',i,round(times[-1],3),flush=True)
vectors=np.asarray(vectors)
same=[];diff=[]
for i in range(len(vectors)):
 for j in range(i):
  c=float(vectors[i]@vectors[j]);(same if i%4==j%4 else diff).append(c)
print('same median/range',np.median(same),min(same),max(same),flush=True)
print('diff median/range',np.median(diff),min(diff),max(diff),flush=True)
results=[]
for threshold in [.35,.4,.45,.5,.55,.6,.65,.7,.75,.8]:
 centroids=[];counts=[];assigned=[]
 for v in vectors:
  similarities=[float(v@c) for c in centroids]
  best=int(np.argmax(similarities)) if similarities else -1
  if best<0 or similarities[best]<threshold:
   best=len(centroids);centroids.append(v.copy());counts.append(1)
  else:
   counts[best]+=1;c=centroids[best]*(counts[best]-1)+v;c/=np.linalg.norm(c);centroids[best]=c
  assigned.append(best)
 # Pairwise correctness penalizes speaker splits and merges without label permutations.
 correct=total=0
 for i in range(len(assigned)):
  for j in range(i):
   correct+=((assigned[i]==assigned[j])==(i%4==j%4));total+=1
 results.append({'threshold':threshold,'clusters':len(centroids),'pairwise':round(correct/total,3),'ids':assigned})
 print('threshold',threshold,'clusters',len(centroids),'pairwise',round(correct/total,3),flush=True)
out=root/'.tools/bench/diarization'/('embedding-results.json' if not crop_seconds else f'embedding-{crop_seconds:g}s.json')
out.write_text(json.dumps({'turns':len(spans),'same_median':float(np.median(same)),'diff_median':float(np.median(diff)),'compute_seconds':sum(times),'results':results},indent=2))
