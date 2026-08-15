# ArenaMP Mobile Client V1

Android builder for the ArenaMP client from `https://github.com/pporsilkde/AMP` (`main` by default).

This package builds **the client only** (`tes3mp` -> `libtes3mp.so`). The ArenaMP/TES3MP server, master server and server browser are intentionally disabled in the Android CMake configuration.

## What was ported

- Current ArenaMW Mobile Android/NG-GL4ES builder base.
- RakNet/CrabNet Android dependency checkpoint from the older working ArenaMP Android builder.
- Rebased Android engine patch set as one deterministic AMP cumulative patch.
- Sisah2/NG-GL4ES `Openmw3` renderer path and NDK r21e compatibility.
- Android touch controls, UI fixes, mobile graphics limits/presets, shadow fixes, simple-water mobile policy, quick-loot/collision fixes and the current Android performance tuning from the donor builder.
- ArenaMP/TES3MP network client startup through `libtes3mp.so`.
- Desktop-compatible `build.ini` server endpoint handling.

## Native build

```bash
cd buildscripts
./build.sh --arch arm64 --ccache --release --client-only
```

The root `./build.sh` is a convenience wrapper for the same command.

Expected client artifact before Gradle packaging:

```text
app/src/main/jniLibs/arm64-v8a/libtes3mp.so
```

RakNet checkpoint:

```text
buildscripts/prefix/arm64/lib/libRakNetLibStatic.a
```

## APK build

The included GitHub Actions workflow builds arm64 and publishes:

```text
ArenaMP-arm64-client.apk
ArenaMP-arm64-client.apk.sha256
```

The Android/Gradle baseline is deliberately kept compatible with the existing builder: NDK r21e, compileSdk 29, targetSdk 28, AGP 4.0.2 / Gradle 6.1.1.

## build.ini / server endpoint

Android uses the same portable keys as the current ArenaMP desktop launcher. Example:

```ini
[Build]
format=1
name="ArenaMP"
data-path="Data Files"
language="Russian"
complete=false

[Server]
address="192.168.1.10"
port="25565"
vanilla-build-server=false

[Content]
content="Morrowind.esm"
content="Tribunal.esm"
content="Bloodmoon.esm"

[Archives]
archive="Morrowind.bsa"
archive="Tribunal.bsa"
archive="Bloodmoon.bsa"
```

Accepted server address aliases: `address`, `ip`, `host`.
Accepted complete aliases: `complete`, `locked`, `read-only`.
Accepted vanilla compatibility aliases: `vanilla-build-server`, `vanilla`, `legacy-client`.

### complete=false

- Server IP/host and port are editable in the Android launcher.
- Changes are written back to `build.ini`.
- Mod enabled state and load order are written back to the same manifest.

### complete=true

- The endpoint from `build.ini` is authoritative and cannot be overwritten from Android server fields.
- The selected endpoint is still shown in the launcher for diagnostics.
- Mods/Data Files remain available, matching the current desktop ArenaMP launcher behavior where deliberate content-order changes can still be written to the manifest.
- `vanilla-build-server` is taken from `build.ini` and passed to the client when enabled.

## Client arguments

At launch Android invokes the TES3MP client with:

```text
--connect=<address>:<port>
```

and, when requested by the manifest:

```text
--vanilla-build-server
```

No local server is started or packaged in V1.

## Patch layout

The active AMP engine patch is:

```text
buildscripts/patches/openmw/06-arenamp-mobile-cumulative-v1.patch
```

It is rebased for the supplied AMP snapshot and replaces the conflicting ArenaMW patch chain during the Android build. The original donor patches are retained under:

```text
buildscripts/patches/openmw/legacy-arenamw/
```

for audit/reference only; CMake does not apply them individually.

## Source override

```bash
ARENAMP_REPOSITORY=https://github.com/pporsilkde/AMP.git \
ARENAMP_GIT_TAG=main \
./buildscripts/build.sh --arch arm64 --ccache --release --client-only
```

A future upstream `AMP:main` change can legitimately make the cumulative patch fail. In that case rebase the cumulative patch against the new AMP revision instead of forcing partial hunks.
