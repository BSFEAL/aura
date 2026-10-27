# Android experimental APK signing

Android accepts an update only when application ID and signing certificate match and the version code does not decrease. Uninstalling removes app-private rules and history. Never uninstall as a migration test workaround.

The Android workflow now includes `signing-report.json` beside `app-debug.apk`. It records the actual certificate SHA-256, APK SHA-256, byte size, and `retained` or `ephemeral` signing mode. Compare the certificate with the installed APK's report before updating. Retained signing does not certify compatibility with an older installation signed by another key.

## Maintainer setup (one time)

Use a trusted workstation with JDK 17. Prefer the existing installed APK's original key if it was retained. An APK contains only the public certificate; its private key cannot be recovered from it. If the original key is lost, continuity cannot be restored for that installation.

If a new distribution key is needed, generate it outside this repository; keytool prompts for passwords so they are not stored in shell history:

```sh
keytool -genkeypair -keystore /secure/backup/aura-debug.jks -alias aura-debug -keyalg RSA -keysize 3072 -validity 10000
keytool -list -v -keystore /secure/backup/aura-debug.jks -alias aura-debug
```

Back up the key and credentials securely under maintainer control. Losing or rotating this key breaks in-place updates. Do not commit or upload the keystore as an artifact. This remains an experimental **debuggable** application, not a production release-signing arrangement.

In GitHub repository Settings → Secrets and variables → Actions configure:

| Setting | Type | Value |
| --- | --- | --- |
| `AURA_SIGNING_KEYSTORE_BASE64` | Secret | Single-line base64 of the keystore |
| `AURA_SIGNING_STORE_PASSWORD` | Secret | Keystore password |
| `AURA_SIGNING_KEY_ALIAS` | Secret | Key alias |
| `AURA_SIGNING_KEY_PASSWORD` | Secret | Key password |
| `AURA_SIGNING_CERT_SHA256` | Variable | SHA-256 certificate fingerprint from keytool (hex, with or without colons) |

Base64 is encoding, not encryption: paste only into a GitHub **secret**, never an issue, chat, workflow file or repository variable. An administrator must provision these values; implementation of the workflow does not mean they are configured.

Only main-branch builds receive these values. PR and feature-branch APKs use ephemeral debug signing and must not be used to promise seamless updates. Protect main and review build scripts before merging: code executed on main can access the signing secrets. No `pull_request_target` job is used.

Main without any signing configuration keeps the existing ephemeral build workflow, explicitly labeled in its report. Any partial configuration fails. A configured build verifies the signed APK certificate against the pinned fingerprint before upload. The temporary keystore is outside the checkout, mode 0600, and deleted even on build failure. GitHub-hosted runners are disposable; do not run signing builds on shared persistent runners without separate isolation.

After configuring, run **Android debug APK → Run workflow → main**. Download its artifact and inspect `signing-report.json`. Keep using retained-main APKs with that same certificate. Future releases must increase versionCode; same-code experimental updates require the same key and platform acceptance.

## Local verification

The Gradle debug signing override reads `AURA_SIGNING_STORE_FILE`, `AURA_SIGNING_STORE_PASSWORD`, `AURA_SIGNING_KEY_ALIAS`, and `AURA_SIGNING_KEY_PASSWORD`. Do not put passwords in command arguments or checked-in Gradle properties. Supply them through a trusted local secret manager/environment. Without an override, Android Studio uses its normal local debug key.

After building, verify the actual APK:

```sh
python tools/android_signing.py report --apk android/app/build/outputs/apk/debug/app-debug.apk --apksigner "$ANDROID_HOME/build-tools/34.0.0/apksigner" --output /tmp/aura-signing-report.json
```

Set `AURA_SIGNING_CERT_SHA256` to require a specific certificate. A mismatch or invalid signature fails rather than publishing a misleading report. Use `adb install -r app-debug.apk` on a test device with seeded rules/history for the full upgrade test. Matching signatures alone do not prove data migration; the physical upgrade matrix remains required.
