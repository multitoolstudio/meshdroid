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

`.gitignore` excludes the built binaries (`app/src/main/jniLibs/arm64-v8a/*.so`,
which is both daemons and `libc++_shared.so`),
the cross-built libraries (`firmware/android-prefix/`, `firmware/deps-src/`),
Gradle and Studio state, and any signing material (`keystore.properties`,
`*.jks`). It deliberately does not exclude `gradle/wrapper/gradle-wrapper.jar`:
the wrapper is committed on purpose so that a fresh clone can run `./gradlew`
without Android Studio. The `meshdroid-notes/` folder beside the repo in the zip
is personal working material and is not part of the repository.

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

Record what changed in `CHANGELOG.md`, including every pin that moved.

## Building the release APK

1. Rebuild whichever daemons changed:
   `cd firmware && ./build-meshtasticd-android.sh ~/meshtastic-firmware`, and
   `cd firmware/meshcore && ./build-meshcored-android.sh ~/meshcore-firmware`.
   Each prints what it built from at the end; both belong in the release notes,
   and they are the same values pinned in `FW_COMMIT_DEFAULT` and
   `MC_TAG_DEFAULT`. A release APK must contain both binaries, even though a
   development build works with either alone.
2. Bump `versionCode` and `versionName`.
3. Android Studio: Build > Generate Signed App Bundle / APK > APK >
   release. Or from a terminal, with the wrapper that ships in the repository:
   `./gradlew assembleRelease`.
4. The APK is at `app/release/app-release.apk`. Rename it
   `meshdroid-<versionName>.apk` for the release page.
5. Install it over the current build on a phone and confirm the node starts
   and the settings survived: `adb install -r meshdroid-<versionName>.apk`.

## Release checklist

- [ ] Both daemons rebuilt from their pinned upstreams; both recorded in
      `CHANGELOG.md` and the release notes
- [ ] Both build scripts' binary checks passed. They fail the build when a string
      that only exists if a patch applied is missing from the binary:
      `API server binding` and `Using pre-opened CH341 fd` for meshtasticd,
      and `Using pre-opened CH341 fd`, `MESHDROID_PATCH_0001_R1`,
      `companion: listening on` and `Meshdroid MeshToad V3` for meshcored
- [ ] `versionCode` incremented, `versionName` updated,
      `DAEMON_FIRMWARE_COMMIT` and `MESHCORE_COMMIT` match the pins in the two
      build scripts
- [ ] `CHANGELOG.md` updated
- [ ] Release APK built, signed with the project key, installed and tested on
      a phone: fresh install and update over the previous version
- [ ] Both protocols start, reach *Node running*, and accept their own phone app
- [ ] Switching protocol with the node running stops it and starts the other one;
      switching with the radio unplugged does not crash the app
- [ ] With the LAN switch off under Meshtastic, the log shows
      `API server binding 127.0.0.1:4403` and no bind-address warning
- [ ] Stop, Restart and Close Meshdroid each leave no daemon behind:
      `adb shell ps -A | grep -E 'meshtasticd|meshcored'` comes back empty
- [ ] Git tag `v<versionName>` pushed
- [ ] Release created on the git host with the APK attached, both upstream
      versions, and a link to the patches in `firmware/` and
      `firmware/meshcore/patches/`

## Licence obligations

The APK contains a binary built from GPL-3.0 source (meshtastic/firmware plus
the patches in `firmware/`). Distributing the APK therefore requires making
the corresponding source available. Publishing this repository, with the
firmware commit hash in each release's notes, satisfies that: anyone can
reproduce the binary from the named upstream commit, the patches and the build
scripts. Pinning the commit in `build-meshtasticd-android.sh` is part of that
promise, since a build of whatever `master` was on a given day is not
reproducible later.

`meshcored` is built from meshcore-dev/MeshCore, which is MIT, so redistribution
imposes less. Two things in it are GPL-3.0 regardless: Meshdroid's own patches
and variant, and `firmware/meshcore/variant/USBHal.h`, which is vendored from
meshtastic/firmware. The same promise therefore applies to it in practice, and
the tag is pinned in `build-meshcored-android.sh` for the same reason. MIT into
GPL-3 is the direction that works; the reverse is not, which is why the CH341 HAL
being GPL is a note about this binary rather than a problem.

The APK also ships the NDK's `libc++_shared.so`, which is part of the LLVM
project and carries the Apache 2.0 licence with LLVM exceptions. It is
redistributed unmodified.

Meshdroid's own code is GPL-3.0 as well; see `LICENSE`.
