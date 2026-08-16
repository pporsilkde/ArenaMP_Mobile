# ArenaMP Mobile Client V1.2

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
buildscripts/patches/openmw/06-arenamp-mobile-cumulative-v1-2.patch
```

It is rebased for the supplied AMP snapshot and replaces the conflicting ArenaMW patch chain during the Android build. The original donor patches are retained under:

```text
buildscripts/patches/openmw/legacy-arenamw/
```

for audit/reference only; CMake does not apply them individually.

## Source override

```bash
ARENAMP_REPOSITORY=https://github.com/pporsilkde/AMP.git \
ARENAMP_GIT_TAG=0f659371bcbaf9e7e6b94bd6bcb7a81970082234 \
./buildscripts/build.sh --arch arm64 --ccache --release --client-only
```

A future upstream `AMP:main` change can legitimately make the cumulative patch fail. In that case rebase the cumulative patch against the new AMP revision instead of forcing partial hunks.


## V1.2 render/input fix

- Android now hard-disables the complete ArenaMP native fullscreen post-processing chain (SMAA, Bloom, atmospheric fog, god rays, sharpening and dithering). This prevents the NG-GL4ES black-world/visible-GUI failure after the first rendered frame.
- Launcher also writes sharpening/dithering disabled, so stale settings cannot reactivate the chain.
- The former Wait/T button keeps its stored OSC position, uses `save.png`, sends `Y` on a short tap and `F2` on a 650 ms hold.
- Holding the scroll-wheel control for 650 ms without dragging sends `TAB`; normal scroll gestures are unchanged.
- The combined keyboard/F11/F12 control is at virtual `(12, 528)`, directly below the scroll wheel and X-aligned with Pause.
- Pause default alpha is 0.52 (less transparent).
