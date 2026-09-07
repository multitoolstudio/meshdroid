# Releasing

## Contents

- [Publishing the repository from the source zip](#publishing-the-repository-from-the-source-zip)
- [Signing key](#signing-key)
- [Version numbers](#version-numbers)
- [Building the release APK](#building-the-release-apk)
- [Release checklist](#release-checklist)
- [Licence obligations](#licence-obligations)

## Publishing the repository from the source zip

Create an empty repository on the git host first (no README, no licence, no
.gitignore, so the first push is not a merge). Then:

```bash
unzip meshdroid-1.0.zip
cd meshdroid-1.0/meshdroid
git init -b main
git add -A
git status                       # confirm no .so, android-prefix, deps-src or keystore files
git commit -m "Meshdroid 1.0.0"
git remote add origin <your-server-url>
git push -u origin main
```

For a second host such as GitHub, add another remote and push the same
branch:

```bash
git remote add github https://github.com/<org>/meshdroid.git
git push -u github main
```

`.gitignore` excludes the built binaries (`app/src/main/jniLibs/arm64-v8a/*.so`),
the cross-built libraries (`firmware/android-prefix/`, `firmware/deps-src/`),
Gradle and Studio state, and any signing material (`keystore.properties`,
`*.jks`). The `meshdroid-notes/` folder beside the repo in the zip is
personal working material and is not part of the repository.

## Signing key

Every update to an installed app must be signed with the same key, so the
keystore is a long-term asset. Create it once, keep it outside the repository,
and back it up.

In Android Studio: Build > Generate Signed App Bundle / APK > APK > Create
new... Store it somewhere like `~/keys/meshdroid.jks` with alias `meshdroid`.

Then create `keystore.properties` in the repository root. It is gitignored and
read by `app/build.gradle.kts`:

```
storeFile=/home/you/keys/meshdroid.jks
storePassword=...
keyAlias=meshdroid
keyPassword=...
```

With that file present, the `release` build type signs automatically.

## Version numbers

Both live in `app/build.gradle.kts` under `defaultConfig`:

- `versionName` is what people see. Semantic versioning: `1.0.0`, `1.0.1`,
  `1.1.0`.
- `versionCode` is an integer Android compares. It must increase with every
  release, otherwise the installer refuses to update over the previous
  version.

Record what changed in `CHANGELOG.md`.

## Building the release APK

1. Rebuild the daemon if the firmware or patches changed:
   `cd firmware && ./build-meshtasticd-android.sh ~/meshtastic-firmware`.
   Note the firmware commit hash from the build output (`firmware version
   2.x.y.<hash>`); it belongs in the release notes.
2. Bump `versionCode` and `versionName`.
3. Android Studio: Build > Generate Signed App Bundle / APK > APK >
   release. Or from a terminal with the wrapper present:
   `./gradlew assembleRelease`.
4. The APK is at `app/release/app-release.apk`. Rename it
   `meshdroid-<versionName>.apk` for the release page.
5. Install it over the current build on a phone and confirm the node starts
   and the settings survived: `adb install -r meshdroid-<versionName>.apk`.

## Release checklist

- [ ] Firmware rebuilt from a known commit; hash recorded
- [ ] `versionCode` incremented, `versionName` updated
- [ ] `CHANGELOG.md` updated
- [ ] Release APK built, signed with the project key, installed and tested on
      a phone: fresh install and update over the previous version
- [ ] Git tag `v<versionName>` pushed
- [ ] Release created on the git host with the APK attached, the firmware
      commit hash, and a link to the patches in `firmware/`

## Licence obligations

The APK contains a binary built from GPL-3.0 source (meshtastic/firmware plus
the patches in `firmware/`). Distributing the APK therefore requires making
the corresponding source available. Publishing this repository, with the
firmware commit hash in each release's notes, satisfies that: anyone can
reproduce the binary from the named upstream commit, the patches and the build
scripts.

Meshdroid's own code is GPL-3.0 as well; see `LICENSE`.
