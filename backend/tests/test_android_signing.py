"""Signing policy tests; no repository secrets or network access needed."""
import base64
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('android_signing', ROOT / 'tools/android_signing.py')
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


def settings(tmp_path):
    return dict(zip(signing.SECRET_NAMES, [base64.b64encode(b'x' * 64).decode(), 'password', 'alias', 'password']),
                AURA_SIGNING_CERT_SHA256='ab' * 32, RUNNER_TEMP=str(tmp_path), GITHUB_ENV=str(tmp_path / 'env'))


def test_missing_signing_is_explicit_and_partial_config_fails(tmp_path, capsys):
    assert signing.prepare({}) is None
    assert 'Ephemeral' in capsys.readouterr().out
    for missing in (*signing.SECRET_NAMES, 'AURA_SIGNING_CERT_SHA256'):
        env = settings(tmp_path)
        del env[missing]
        with pytest.raises(ValueError):
            signing.prepare(env)
    assert not (tmp_path / 'aura-signing.jks').exists()


def test_key_permissions_exclusive_creation_and_validation(tmp_path):
    env = settings(tmp_path)
    path = signing.prepare(env)
    assert path.read_bytes() == b'x' * 64
    assert path.stat().st_mode & 0o777 == 0o600
    assert 'AURA_SIGNING_STORE_FILE=' in (tmp_path / 'env').read_text()
    with pytest.raises(FileExistsError):
        signing.prepare(env)
    path.unlink()
    env['AURA_SIGNING_KEYSTORE_BASE64'] = 'not base64!'
    with pytest.raises(ValueError):
        signing.prepare(env)
    env = settings(tmp_path)
    env['AURA_SIGNING_CERT_SHA256'] = 'bad'
    with pytest.raises(ValueError):
        signing.prepare(env)
    assert not path.exists()


def test_certificate_mismatch_and_multiple_signers_refuse_report(tmp_path, monkeypatch):
    apk = tmp_path / 'app.apk'
    apk.write_bytes(b'apk test data')
    output = tmp_path / 'report.json'
    cert = 'ab' * 32
    stdout = f'Signer #1 certificate SHA-256 digest: {cert}\n'
    monkeypatch.setattr(signing.subprocess, 'run', lambda *a, **k: SimpleNamespace(stdout=stdout))
    data = signing.report(apk, Path('apksigner'), cert.upper(), output)
    assert data['signing_mode'] == 'retained'
    assert json.loads(output.read_text())['certificate_sha256'] == cert
    output.unlink()
    with pytest.raises(ValueError):
        signing.report(apk, Path('apksigner'), 'cd' * 32, output)
    assert not output.exists()
    stdout += f'Signer #2 certificate SHA-256 digest: {cert}\n'
    with pytest.raises(ValueError):
        signing.report(apk, Path('apksigner'), '', output)
