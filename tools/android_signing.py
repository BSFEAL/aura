"""Prepare optional retained debug signing and report the actual APK certificate.

Secrets are read from the environment, never passed as command arguments or logged.
The expected certificate fingerprint is public and must be pinned separately.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

SECRET_NAMES = (
    'AURA_SIGNING_KEYSTORE_BASE64', 'AURA_SIGNING_STORE_PASSWORD',
    'AURA_SIGNING_KEY_ALIAS', 'AURA_SIGNING_KEY_PASSWORD',
)


def fingerprint(value):
    normalized = value.replace(':', '').lower()
    if not re.fullmatch(r'[a-f0-9]{64}', normalized):
        raise ValueError('Expected certificate must be a SHA-256 fingerprint')
    return normalized


def prepare(env):
    values = [env.get(name, '') for name in SECRET_NAMES]
    expected = env.get('AURA_SIGNING_CERT_SHA256', '')
    if not any(values) and not expected:
        print('Ephemeral debug signing: this APK is not guaranteed to update previous installs.')
        return None
    if not all(values) or not expected:
        raise ValueError('Retained signing requires all four signing secrets and AURA_SIGNING_CERT_SHA256')
    fingerprint(expected)
    try:
        content = base64.b64decode(values[0], validate=True)
    except ValueError:
        raise ValueError('Signing keystore is not valid base64') from None
    if not 32 <= len(content) <= 1024 * 1024:
        raise ValueError('Signing keystore size is invalid')
    target = Path(env['RUNNER_TEMP']) / 'aura-signing.jks'
    # Exclusive creation prevents overwriting an existing key or following a symlink.
    with target.open('xb') as handle:
        os.chmod(target, 0o600)
        handle.write(content)
    with Path(env['GITHUB_ENV']).open('a') as handle:
        handle.write(f'AURA_SIGNING_STORE_FILE={target}\n')
    print('Retained signing prepared; the APK certificate will be checked before upload.')
    return target


def report(apk, apksigner, expected, output):
    result = subprocess.run(
        [str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)],
        check=True, capture_output=True, text=True,
    )
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$', result.stdout, re.M)
    if len(certificates) != 1:
        raise ValueError('Expected exactly one APK signing certificate')
    actual = fingerprint(certificates[0])
    if expected and actual != fingerprint(expected):
        raise ValueError('APK signing certificate does not match the pinned fingerprint; refusing artifact')
    data = {
        'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
        'apk_bytes': apk.stat().st_size,
        'certificate_sha256': actual,
        'signing_mode': 'retained' if expected else 'ephemeral',
        'upgrade_compatibility': 'requires matching installed certificate and nondecreasing version code',
    }
    output.write_text(json.dumps(data, indent=2) + '\n')
    print(json.dumps(data, indent=2))
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('prepare')
    check = commands.add_parser('report')
    check.add_argument('--apk', required=True, type=Path)
    check.add_argument('--apksigner', required=True, type=Path)
    check.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            prepare(os.environ)
        else:
            report(args.apk, args.apksigner, os.environ.get('AURA_SIGNING_CERT_SHA256', ''), args.output)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Never echo subprocess output or configuration values (may include secret material).
        print(f'Signing check failed: {error}' if isinstance(error, ValueError)
              else 'Signing check failed: file or signing-tool error', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
