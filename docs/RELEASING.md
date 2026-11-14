# Releasing

A release is a **draft** GitHub release made from a tag. Nothing is published until the owner reads the draft and presses *Publish*. The workflow is `.github/workflows/release.yml` (decision D30).

## What a release contains

| File | Built on | Signed |
|---|---|---|
| `aeon-<version>-android.apk` | Linux runner | with the release key, **once the secrets below exist** |
| `aeon-<version>-android-debug-key.apk` | Linux runner | with the debug key: installs for testing, cannot be updated by a later release-key build. Left out of the release when the signed one exists |
| `Aeon-<version>.msi`, `Aeon-<version>.exe` | Windows runner | not signed (Windows SmartScreen will warn) |
| `aeon_<version>-1_amd64.deb` | Linux runner | not signed |
| `aeon-<version>-web.zip` | Linux runner | the web app; start the server with `--web <folder>` |
| `SHA256SUMS.txt` | | checksums of all of the above |

Not built: macOS and iOS (out of scope), Windows ARM64 and rpm packages (a `jpackage` build runs only on the machine it is on), an automatic updater, Google Play.

## Making a release

1. `main` is green and contains what should ship.
2. A repository administrator creates the tag (only administrators can: see *Protections*):
   ```bash
   git tag v1.2.3
   git push origin v1.2.3
   ```
   `v1.2.3-rc1` is also accepted and makes a pre-release. A version below 1.0.0 is always a pre-release. Anything else (`v1`, `v1.2`, `release-1`) stops at the first step.
3. The workflow runs the CI checks, then builds the Android app, the desktop installers and the web app. The signed Android build **waits for the owner's approval** (GitHub shows *Review deployments* on the run).
4. When everything is built, the draft release appears under *Releases* with generated notes. Read it, edit the notes, press *Publish*.

The version comes from the tag: `1.2.3` becomes the Android `versionName`, `10203` its `versionCode` (major × 10000 + minor × 100 + patch, so it only grows), and the numeric part is the Windows and Linux package version.

## The signing key and the secrets

The Android release key is the app's identity: **a phone installs an update only if it is signed with the same key as the installed version.** Make it yourself, on your own computer, and keep two copies of the file and its passwords somewhere safe (a password manager and an offline copy). If it is lost, every installed copy has to be uninstalled and installed again; GitHub secrets cannot be read back, so they are not a backup.

```bash
keytool -genkeypair -v -keystore aeon-release.jks -alias aeon -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 aeon-release.jks > aeon-release.jks.b64        # on Windows (Git Bash) the same command works

gh secret set ANDROID_KEYSTORE_BASE64   --env release --repo spear34000/Aeon-Agent < aeon-release.jks.b64
gh secret set ANDROID_KEYSTORE_PASSWORD --env release --repo spear34000/Aeon-Agent
gh secret set ANDROID_KEY_ALIAS         --env release --repo spear34000/Aeon-Agent
gh secret set ANDROID_KEY_PASSWORD      --env release --repo spear34000/Aeon-Agent
rm aeon-release.jks.b64
```

`gh secret set` asks for the three short values on the keyboard; they never go into a file or the shell history. The secrets belong to the **`release` environment**, not to the repository, so a workflow run that does not go through the environment (a pull request, a branch) cannot read them.

Until the secrets exist the workflow still works: the signed job says so and the release gets the debug-key APK only.

## Protections

- **Environment `release`**: needs the owner's approval for every run that uses it, and runs only for tags matching `v*`.
- **Tag ruleset `Release tags`**: `v*` tags can be created, moved and deleted by repository administrators only. A collaborator with write access cannot start a release.
- The workflow's own token is read-only except in the last job, which only creates the *draft*.
- Pull requests that change `release.yml` build everything once (a dry run, no release, no secrets) so the workflow is tested before it is used. A pull request from a fork does not get secrets at all.

## What has and has not been tried

See decision D30 in [DECISIONS.md](DECISIONS.md) for the state of the first real run.
