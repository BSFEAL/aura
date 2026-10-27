"""Actual LiteRT inference; host measurements are not Android power/performance claims."""
import argparse
import json
import platform
import time
from pathlib import Path
import numpy as np
from ai_edge_litert.interpreter import Interpreter
from verify_model import ROOT, verify


def run():
    manifest = verify()
    start = time.perf_counter()
    interpreter = Interpreter(model_path=str(ROOT / 'android/app/src/main/assets/yamnet.tflite'), num_threads=2)
    interpreter.allocate_tensors()
    init_ms = (time.perf_counter()-start)*1000
    ins, outs = interpreter.get_input_details(), interpreter.get_output_details()
    assert len(ins) == len(outs) == 1
    assert ins[0]['shape'].tolist() == [15600] and ins[0]['dtype'] == np.float32
    assert outs[0]['shape'].tolist() == [1,521] and outs[0]['dtype'] == np.float32
    samples = np.frombuffer((ROOT / manifest['fixture']['path']).read_bytes(), dtype='<i2').astype(np.float32)/32768
    times = []
    for _ in range(12):
        interpreter.set_tensor(ins[0]['index'], samples)
        start = time.perf_counter(); interpreter.invoke(); times.append((time.perf_counter()-start)*1000)
    scores = interpreter.get_tensor(outs[0]['index'])[0]
    assert np.isfinite(scores).all() and ((scores >= 0) & (scores <= 1)).all()
    bark = manifest['events']['dog_bark'][0]
    assert bark['index'] == 70 and bark['label'] == 'Bark'
    assert float(scores[bark['index']]) > .65, 'Deterministic bark fixture did not produce the expected model response'
    return dict(model_version=manifest['model_version'], fixture_sha256=manifest['fixture']['sha256'],
                platform=platform.platform(), python=platform.python_version(), init_ms=init_ms,
                average_inference_ms=float(np.mean(times)), p95_inference_ms=float(np.percentile(times,95)),
                windows=12, bark_score=float(scores[70]), model_bytes=manifest['assets']['yamnet.tflite']['bytes'],
                calibrated=False, interpretation='Fixture smoke test only; host CPU timings, not Android or accuracy measurements')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--report', type=Path); args = parser.parse_args()
    report = run(); text = json.dumps(report, indent=2)
    if args.report: args.report.parent.mkdir(parents=True,exist_ok=True); args.report.write_text(text+'\n')
    print(text)
