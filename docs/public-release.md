# MiDuo public release process
## Build
Android API 31+, compile SDK 36, JDK 17+.
```sh
./gradlew --no-daemon -PduoProjectionTestsOnly=true :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```
Windows: gradlew.bat. Provide ANDROID_HOME or local.properties locally; never commit local.properties.
Without all four DUO_RELEASE_STORE_FILE / DUO_RELEASE_STORE_PASSWORD / DUO_RELEASE_KEY_ALIAS / DUO_RELEASE_KEY_PASSWORD values, Release stays unsigned. Keys must live outside the repository; public CI has no signing secrets.

## Export and audit
PUBLIC-FILES lists the exact public files and approved source subtrees. Run:
```sh
./scripts/export-public-source.sh /tmp/MiDuo-public
./scripts/check-public-source.sh /tmp/MiDuo-public
```
Use a fresh destination. Never publish the private worktree or its Git history. Review the final tree, binary origins and licenses, then scan for credentials, private keys, real codes, customer data, captures and local paths. Automated scans reduce risk but are not a guarantee.

Only the Android client, build scripts, client tests and public documentation are released. Exclude issuer/store infrastructure, deployment scripts, databases, private keys, signing files and customer records. Public verification keys and activation URLs are not secrets.

## 1.0.2 GitHub release
Confirm the exact repository owner/name and public visibility. Create a clean Git history from the audited export, commit and tag the reviewed snapshot v1.0.2.
Attach the **existing official website APK**, its SHA256, and audited source archive; do not re-sign or silently replace 1.0.2 with a new build.
Inspect the release draft and CI results before publication. Do not claim byte-for-byte reproducibility unless verified. APK checksums and signing identity are in [release notes](releases/1.0.2.md).
Self-built/debug packages use a different signing identity and may not update the official package; preserve user data and plan migration.

## Licenses and version policy
Retain Duo Launcher, GlassProjection, Shizuku and all dependency notices. MiDuo additions are attributed to D.JT; upstream authors retain their rights. MIT permits redistribution, including modified builds. Official activation infrastructure and signing identity are separate from the source license.
The website APK can be newer than published source. Clearly label the public tag and official download version. Do not attach a newer APK to a tag while implying its source corresponds to that tag.
