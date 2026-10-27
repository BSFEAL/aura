"""Offline score/episode evaluation. Standard library for saved scores; optional WAV inference.

JSON input: {kind: synthetic|real, dataset: str, frames:[{clip_id,time_ms,scores:{event:score},labels:[event]}],
 truth_episodes:[{clip_id,event_type,start_ms,end_ms}], recordings?:[{source_id,split,fold}]}
"""
import argparse
import json
import math
from pathlib import Path

EVENTS = ('dog_bark','alarm_siren','rain','running_water','knocking','doorbell')


def validate(data):
    if data.get('kind') not in ('synthetic','real') or not data.get('dataset'):
        raise ValueError('Explicit dataset and kind required')
    groups = {}
    for row in data.get('recordings',[]):
        if row['split'] not in ('train','validation','test'):
            raise ValueError('Invalid split')
        if row['source_id'] in groups and groups[row['source_id']] != row['split']:
            raise ValueError('Recording source leaks across splits')
        groups[row['source_id']] = row['split']
    times = {}
    frames = data['frames']
    if not frames:
        raise ValueError('No labeled frames')
    for frame in frames:
        clip = frame['clip_id']; now = frame['time_ms']
        if not isinstance(now,int) or isinstance(now,bool) or now < 0 or now <= times.get(clip,-1):
            raise ValueError('Frames must be strictly ordered per clip on a monotonic clock')
        times[clip] = now
        if not set(frame['labels']) <= set(EVENTS) or not set(frame['scores']) <= set(EVENTS):
            raise ValueError('Unsupported event label')
        if not all(isinstance(x,(int,float)) and not isinstance(x,bool) and math.isfinite(x) and 0 <= x <= 1 for x in frame['scores'].values()):
            raise ValueError('Scores must be finite in [0,1]')
    for episode in data.get('truth_episodes',[]):
        if episode['event_type'] not in EVENTS or episode['end_ms'] < episode['start_ms']:
            raise ValueError('Invalid truth episode')


def metrics(tp, fp, fn, tn=0):
    precision = tp/(tp+fp) if tp+fp else 0.0
    recall = tp/(tp+fn) if tp+fn else 0.0
    return dict(precision=precision,recall=recall,f1=2*precision*recall/(precision+recall) if precision+recall else 0.0,
                false_positives=fp,confusion_matrix={'labels':['negative','positive'],'rows_actual_columns_predicted':[[tn,fp],[fn,tp]]})


def temporal(frames, event, threshold, release, alpha, duration, gap, cooldown):
    """Same transitions as Kotlin audio detector; discontinuity >10 s resets evidence."""
    state='IDLE'; smooth=None; candidate=last=previous=None; until=0; episodes=[]; active=None; below=False
    for frame in frames:
        now=frame['time_ms']; raw=frame['scores'].get(event,0.0)
        if previous is not None and now-previous>10000:
            if active is not None: active['end_ms']=previous
            state='IDLE';smooth=None;active=None;until=0
        previous=now
        if below and last is not None and now-last>gap:
            if state=='CANDIDATE': state='IDLE'
            elif state=='ACTIVE':
                active['end_ms']=last;active=None;state='COOLDOWN' if now<until else 'IDLE';smooth=None;below=False
                continue
        smooth=raw if smooth is None else alpha*raw+(1-alpha)*smooth
        if state=='COOLDOWN' and now>=until: state='IDLE'
        if state=='IDLE' and smooth>=threshold:
            state='CANDIDATE';candidate=last=now;below=False
        if state=='CANDIDATE':
            below=smooth<threshold
            if not below: last=now
            if now-last>gap: state='IDLE'
            elif smooth>=threshold and now-candidate>=duration and now>=until:
                state='ACTIVE';until=now+cooldown
                active=dict(clip_id=frame['clip_id'],event_type=event,start_ms=candidate,end_ms=now)
                episodes.append(active)
        elif state=='ACTIVE':
            below=smooth<release
            if not below: last=now
            if now-last>gap:
                active['end_ms']=last;active=None;state='COOLDOWN' if now<until else 'IDLE'
            else: active['end_ms']=last
    return episodes


def evaluate(data, thresholds=(.3,.5,.7,.9), alpha=.5, duration=3000, gap=1000, cooldown=30000):
    validate(data)
    if not 0<alpha<=1 or not 0<=duration<=60000 or not 0<=gap<=10000 or not 0<=cooldown<=3600000:
        raise ValueError('Invalid temporal settings')
    by_clip={}
    for f in data['frames']: by_clip.setdefault(f['clip_id'],[]).append(f)
    reports=[]
    for threshold in thresholds:
        if not 0<threshold<=1: raise ValueError('Threshold outside (0,1]')
        frame_report={};episode_report={}
        for event in EVENTS:
            tp=fp=fn=tn=0
            for f in data['frames']:
                predicted=f['scores'].get(event,0)>=threshold;actual=event in f['labels']
                tp+=predicted and actual;fp+=predicted and not actual;fn+=not predicted and actual;tn+=not predicted and not actual
            frame_report[event]=metrics(tp,fp,fn,tn)
            predicted=[]
            for frames in by_clip.values(): predicted+=temporal(frames,event,threshold,max(0,threshold-.2),alpha,duration,gap,cooldown)
            truth=[e for e in data.get('truth_episodes',[]) if e['event_type']==event]
            matched=set();true_positive=0
            for e in predicted:
                overlaps=[(max(0,min(e['end_ms'],t['end_ms'])-max(e['start_ms'],t['start_ms'])),i) for i,t in enumerate(truth) if i not in matched and e['clip_id']==t['clip_id']]
                overlap,index=max(overlaps,default=(0,-1))
                if overlap>0: matched.add(index);true_positive+=1
            episode_report[event]=dict(**metrics(true_positive,len(predicted)-true_positive,len(truth)-true_positive),predicted_episodes=predicted,matching='one-to-one positive time overlap within same clip')
        reports.append(dict(threshold=threshold,frame_metrics=frame_report,temporal_metrics=episode_report))
    return dict(schema_version=1,dataset=data['dataset'],kind=data['kind'],partition=data.get('partition','unspecified'),calibration='incomplete',
                score_semantics='uncalibrated model output, not real-world probability',
                temporal=dict(ema_alpha=alpha,min_duration_ms=duration,max_gap_ms=gap,cooldown_ms=cooldown),
                limitation='Synthetic data verifies software only. Real scores are dataset-specific and do not establish deployment calibration.',
                threshold_sweep=reports)


def infer_wavs(manifest_path, model_path, split="test"):
    """Manifest: kind/dataset/recordings[{path,clip_id,labels,source_id,split,fold}]. No model download."""
    import numpy as np
    from scipy.io import wavfile
    from scipy.signal import resample_poly
    from ai_edge_litert.interpreter import Interpreter
    from math import gcd
    manifest=json.loads(Path(manifest_path).read_text())
    mapping=json.loads((Path(__file__).resolve().parents[1]/'models/manifest.json').read_text())
    interpreter=Interpreter(model_path=str(model_path),num_threads=2);interpreter.allocate_tensors()
    ins,outs=interpreter.get_input_details()[0],interpreter.get_output_details()[0]
    if ins['shape'].tolist()!=[15600] or outs['shape'].tolist()!=[1,521]: raise ValueError('Wrong model tensors')
    groups={}
    for row in manifest['recordings']:
        if row['source_id'] in groups and groups[row['source_id']]!=row['split']:
            raise ValueError('Recording source leaks across splits')
        groups[row['source_id']]=row['split']
    frames=[]
    for row in manifest['recordings']:
        if row['split']!=split: continue
        path=Path(manifest_path).parent/row['path'];rate,samples=wavfile.read(path)
        if samples.dtype!=np.int16: raise ValueError('Expected signed PCM16 WAV')
        samples=samples.astype(np.float32)/32768
        if samples.ndim==2: samples=samples.mean(axis=1)
        divisor=gcd(int(rate),16000);samples=resample_poly(samples,16000//divisor,rate//divisor).astype(np.float32)
        for start in range(0,len(samples)-15600+1,8000):
            interpreter.set_tensor(ins['index'],samples[start:start+15600]);interpreter.invoke();values=interpreter.get_tensor(outs['index'])[0]
            frames.append(dict(clip_id=row['clip_id'],time_ms=(start+15600)*1000//16000,
                scores={e:max(float(values[c['index']]) for c in classes) for e,classes in mapping['events'].items()},labels=row['labels']))
    return dict(kind=manifest['kind'],dataset=manifest['dataset'],partition=split,frames=frames,recordings=manifest['recordings'],truth_episodes=manifest.get('truth_episodes',[]))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    group=parser.add_mutually_exclusive_group(required=True);group.add_argument('--scores',type=Path);group.add_argument('--audio-manifest',type=Path)
    parser.add_argument('--split',choices=['validation','test'],default='test')
    parser.add_argument('--model',type=Path,default=Path(__file__).resolve().parents[1]/'android/app/src/main/assets/yamnet.tflite')
    parser.add_argument('--report',type=Path,required=True);parser.add_argument('--thresholds',default='0.3,0.5,0.7,0.9')
    parser.add_argument('--alpha',type=float,default=.5);parser.add_argument('--duration-ms',type=int,default=3000)
    parser.add_argument('--gap-ms',type=int,default=1000);parser.add_argument('--cooldown-ms',type=int,default=30000)
    args=parser.parse_args()
    data=json.loads(args.scores.read_text()) if args.scores else infer_wavs(args.audio_manifest,args.model,args.split)
    report=evaluate(data,tuple(map(float,args.thresholds.split(','))),args.alpha,args.duration_ms,args.gap_ms,args.cooldown_ms)
    args.report.parent.mkdir(exist_ok=True,parents=True);args.report.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n')

if __name__=='__main__': main()
