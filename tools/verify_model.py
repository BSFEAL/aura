"""Verify checked-in assets, fixture, embedded labels and AudioSet IDs; no network."""
import csv
import hashlib
import io
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def verify():
    manifest = json.loads((ROOT / 'models/manifest.json').read_text())
    assets = ROOT / 'android/app/src/main/assets'
    for name, info in manifest['assets'].items():
        data = (assets / name).read_bytes()
        assert len(data) == info['bytes'], name
        assert hashlib.sha256(data).hexdigest() == info['sha256'], name
    fixture = manifest['fixture']
    assert hashlib.sha256((ROOT / fixture['path']).read_bytes()).hexdigest() == fixture['sha256']
    labels = (assets / 'yamnet_labels.txt').read_text().splitlines()
    rows = list(csv.DictReader((assets / 'yamnet_class_map.csv').open()))
    assert len(labels) == len(rows) == 521
    assert labels == [r['display_name'] for r in rows]
    assert len({r['mid'] for r in rows}) == 521
    with zipfile.ZipFile(assets / 'yamnet.tflite') as archive:
        assert archive.read('yamnet_label_list.txt').decode().splitlines() == labels
    for classes in manifest['events'].values():
        for entry in classes:
            row = rows[entry['index']]
            assert row['mid'] == entry['class_id'] and row['display_name'] == entry['label']
    return manifest

if __name__ == '__main__':
    manifest = verify()
    print(f"Verified {manifest['model_version']}, 521 aligned classes, fixture and all asset SHA-256 checksums")
