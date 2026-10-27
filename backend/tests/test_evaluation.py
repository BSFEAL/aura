import importlib.util
import json
import subprocess
import sys
from pathlib import Path
import pytest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('evaluate_audio',ROOT/'tools/evaluate_audio.py')
evaluation=importlib.util.module_from_spec(spec);spec.loader.exec_module(evaluation)


def fixture():
    return dict(kind='synthetic',dataset='implementation-fixture',frames=[dict(clip_id='one',time_ms=i*500,scores={'dog_bark':.9 if i<10 else .1},labels=['dog_bark'] if i<10 else []) for i in range(16)],truth_episodes=[dict(clip_id='one',event_type='dog_bark',start_ms=0,end_ms=4500)])


def test_metrics_and_temporal_separation():
    report=evaluation.evaluate(fixture(),(.7,),alpha=1,duration=1000,gap=500,cooldown=3000)
    sweep=report['threshold_sweep'][0]
    assert sweep['frame_metrics']['dog_bark']['f1']==1
    episodes=sweep['temporal_metrics']['dog_bark']
    assert episodes['f1']==1 and len(episodes['predicted_episodes'])==1
    assert report['calibration']=='incomplete'


def test_evaluation_cli_smoke(tmp_path):
    path=tmp_path/'scores.json';path.write_text(json.dumps(fixture()));out=tmp_path/'report.json'
    subprocess.run([sys.executable,str(ROOT/'tools/evaluate_audio.py'),'--scores',str(path),'--report',str(out)],check=True)
    assert len(json.loads(out.read_text())['threshold_sweep'])==4


def test_invalid_scores_and_recording_leakage():
    data=fixture();data['frames'][0]['scores']['dog_bark']=float('nan')
    with pytest.raises(ValueError): evaluation.evaluate(data)
    data=fixture();data['recordings']=[dict(source_id='a',split='test'),dict(source_id='a',split='validation')]
    with pytest.raises(ValueError): evaluation.evaluate(data)
    data=fixture();data['frames'][1]['time_ms']=0
    with pytest.raises(ValueError): evaluation.evaluate(data)
