> Client+Server V1.4: portable server runtime in `/storage/emulated/0/ArenaMP`, config/log in `ArenaMP/config`, CO-OP/MMO + maintenance UI, and Android CJSON compatibility.

# ArenaMP Mobile Client + Server V1

Android builder for the ArenaMP/TES3MP fork from `pporsilkde/AMP`.

This branch is based on **ArenaMP Mobile Client V1.2.10** and builds both:

- `tes3mp` → `libtes3mp.so` — Android client;
- `tes3mp-server` → `libarenamp_server.so` — dedicated ArenaMP server hosted by an Android foreground service.

The default ArenaMP source revision remains pinned to the PC-compatible commit:

`0f659371bcbaf9e7e6b94bd6bcb7a81970082234`

Android `versionCode` remains **47**.

## Build

```bash
cd buildscripts
./build.sh --arch arm64 --ccache --release --client-server
```

The GitHub Actions workflow does the same and publishes an arm64 Client+Server APK.
The first V1.4 run deliberately starts with a clean ArenaMP client/server object tree,
while restoring the existing third-party native dependency checkpoint. After that first
V1.4 build, normal incremental C/C++ rebuilds resume.

Expected native outputs before Gradle packaging:

```text
app/src/main/jniLibs/arm64-v8a/libtes3mp.so
app/src/main/jniLibs/arm64-v8a/libarenamp_server.so
```

## Server runtime on Android

On first use the APK deploys the writable portable runtime to:

```text
/storage/emulated/0/ArenaMP/
├── server/
│   ├── scripts/
│   ├── lib/lua/cjson.lua
│   └── data/
├── resources/
├── config/
│   ├── tes3mp-server.cfg
│   ├── server-config.lua
│   └── tes3mp-server.log
└── Backup/
```

The native server library stays inside the APK, but its working directory is the
portable ArenaMP root. `server/data` is therefore writable shared-storage state.
Legacy V1.0-V1.3 private server data is migrated once.

The Android server page provides CO-OP/MMO presets, required-DataFiles enforcement,
Update Hash, raw `config.lua` editing, cell cleanup and a full gameplay-data reset.

## Launcher integration

Open **Settings → ArenaMP Server** to manage the local server. The Android server page
provides:

- start / stop;
- bind IPv4 address;
- UDP port;
- maximum players;
- hostname and password;
- automatic start;
- automatic restart + backup;
- local LAN address display;
- PC-compatible **Update Hash** generation of `server/data/requiredDataFiles.json` using CRC32;
- live tail of the server log and log clearing.

The launcher mirrors the important PC host-mode behaviour:

- an explicit remote endpoint in `build.ini` makes local auto-start default to off;
- without an explicit remote endpoint, local auto-start / auto-restart default to on;
- local host mode overrides the endpoint only for that launch and does not rewrite the
  remote endpoint in `build.ini`;
- the local server port is synchronized with the Play endpoint;
- the client starts about 900 ms after the server start request;
- stop first requests a graceful native shutdown and uses a 2-second force-stop guard;
- repeated rapid crashes disable the restart loop after three quick failures;
- auto-restart mode creates ZIP backups of the portable server tree.

## Network identity

When the bundled local Android server is used, client and server are built from the same
`arenamp_ref` and use their native/resource network identity.

For a remote PC server the existing independent Parent Network Compatibility fields in
`build.ini` are still supported, so Android application versioning remains separate from
the TES3MP handshake identity.

## Lua runtime

The server links the existing static LuaJIT dependency. Android also packages
`server/lib/lua/cjson.lua`, an API-compatible CJSON layer backed by bundled dkjson,
so CoreScripts no longer emit the missing Lua CJSON error without adding another
native dependency. Windows-only `.dll` modules remain excluded.

## Important files

Native server adaptation:

```text
buildscripts/patches/openmw/09-enable-android-server-host.py
buildscripts/patches/openmw/android_server_jni.cpp
```

Android server frontend:

```text
app/src/main/java/server/ArenaServerService.kt
app/src/main/java/server/ServerActivity.kt
app/src/main/java/server/ServerConfig.kt
app/src/main/java/server/ServerController.kt
app/src/main/java/server/ServerRuntime.kt
app/src/main/java/server/ServerScriptConfig.kt
```

Detailed V1.4 Russian notes are in `ARENAMP_CLIENT_SERVER_V1_4_NOTES_RU.md`.
