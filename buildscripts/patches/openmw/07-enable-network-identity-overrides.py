#!/usr/bin/env python3
"""Add Android-selectable network compatibility identity using semantic anchors.

The script intentionally matches declarations/functions/options, not line numbers. It is
idempotent and fails if an expected source anchor becomes ambiguous.
"""
import os
import re
import sys
from pathlib import Path

if len(sys.argv) < 2:
    raise SystemExit('usage: 07-enable-network-identity-overrides.py <AMP source dir> [compat commit]')
root = Path(sys.argv[1]).resolve()
compat = (sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else os.environ.get('ARENAMP_NETWORK_COMMIT', '')).strip().lower()
if not compat:
    compat = '0f659371bcbaf9e7e6b94bd6bcb7a81970082234'
if not re.fullmatch(r'[0-9a-f]{40}', compat):
    raise SystemExit(f'invalid ArenaMP compatibility commit: {compat!r}')

def load(rel):
    p = root / rel
    if not p.is_file():
        raise SystemExit(f'missing required source file: {rel}')
    return p.read_text(encoding='utf-8')

def save(rel, text):
    (root / rel).write_text(text, encoding='utf-8')

def replace_once(text, old, new, label):
    if new in text:
        return text
    n = text.count(old)
    if n != 1:
        raise SystemExit(f'{label}: expected exactly one semantic anchor, found {n}')
    return text.replace(old, new, 1)

def regex_once(text, pattern, repl, label, flags=0):
    new, n = re.subn(pattern, repl, text, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f'{label}: expected exactly one semantic anchor, found {n}')
    return new

# Shared build-time fallback identity.
rel = 'components/openmw-mp/Version.hpp'
t = load(rel)
t, n = re.subn(r'(#define\s+TES3MP_COMPAT_COMMIT_HASH\s+")[0-9a-fA-F]{40}("\s*)', rf'\g<1>{compat}\2', t, count=1)
if n != 1:
    raise SystemExit('Version.hpp: TES3MP_COMPAT_COMMIT_HASH not found exactly once')
save(rel, t)

# Client CLI state. Current ArenaMP/main intentionally has only connect/password; inject
# Android compatibility controls beside those stable symbols rather than depending on an
# older vanillaBuildServer implementation.
rel = 'apps/openmw/mwmp/Main.cpp'
t = load(rel)
if 'std::string Main::networkVersion' not in t:
    anchor = 'std::string Main::resourceDir = "";'
    add = anchor + '''\nbool Main::vanillaBuildServer = false;\nstd::string Main::networkVersion = "";\nint Main::networkProtocol = 0;\nstd::string Main::networkCommitHash = "";'''
    t = replace_once(t, anchor, add, 'Main.cpp static state')

if 'std::string Main::getNetworkVersion()' not in t:
    anchor = '''std::string Main::getResDir()\n{\n    return resourceDir;\n}\n'''
    add = anchor + '''\nbool Main::useVanillaBuildServer()\n{\n    return vanillaBuildServer;\n}\n\nstd::string Main::getNetworkVersion()\n{\n    return networkVersion.empty() ? TES3MP_VERSION : networkVersion;\n}\n\nint Main::getNetworkProtocol()\n{\n    return networkProtocol > 0 ? networkProtocol : TES3MP_PROTO_VERSION;\n}\n\nstd::string Main::getNetworkCommitHash()\n{\n    return networkCommitHash;\n}\n'''
    t = replace_once(t, anchor, add, 'Main.cpp getters')

if '"network-version"' not in t:
    old = '''            ("password", bpo::value<std::string>()->default_value(TES3MP_DEFAULT_PASSW),\n                        "сonnect to a secured server. (e.g. --password=AnyPassword");'''
    new = '''            ("password", bpo::value<std::string>()->default_value(TES3MP_DEFAULT_PASSW),\n                        "сonnect to a secured server. (e.g. --password=AnyPassword")\n            ("vanilla-build-server", bpo::value<bool>()->implicit_value(true)->default_value(false),\n                        "use the official TES3MP 0.8.1 network identity for older servers")\n            ("network-version", bpo::value<std::string>()->default_value(""),\n                        "override the ArenaMP/TES3MP version advertised during the RakNet handshake")\n            ("network-protocol", bpo::value<int>()->default_value(0),\n                        "override the ArenaMP/TES3MP protocol advertised during the RakNet handshake")\n            ("network-commit-hash", bpo::value<std::string>()->default_value(""),\n                        "override the ArenaMP compatibility commit advertised during the RakNet handshake");'''
    # Older source typo may miss the closing quote in its human description exactly as above.
    t = replace_once(t, old, new, 'Main.cpp command-line options')

if 'Main::networkVersion = variables["network-version"]' not in t:
    anchor = '    Main::serverPassword = variables["password"].as<std::string>();'
    add = anchor + '''\n    Main::vanillaBuildServer = variables["vanilla-build-server"].as<bool>();\n    Main::networkVersion = variables["network-version"].as<std::string>();\n    Main::networkProtocol = variables["network-protocol"].as<int>();\n    Main::networkCommitHash = variables["network-commit-hash"].as<std::string>();'''
    t = replace_once(t, anchor, add, 'Main.cpp configure')
save(rel, t)

rel = 'apps/openmw/mwmp/Main.hpp'
t = load(rel)
if 'static std::string getNetworkVersion();' not in t:
    anchor = '        static std::string getResDir();'
    add = anchor + '''\n        static bool useVanillaBuildServer();\n        static std::string getNetworkVersion();\n        static int getNetworkProtocol();\n        static std::string getNetworkCommitHash();'''
    t = replace_once(t, anchor, add, 'Main.hpp getters')
if 'static std::string networkVersion;' not in t:
    anchor = '        static std::string serverPassword;'
    add = anchor + '''\n        static bool vanillaBuildServer;\n        static std::string networkVersion;\n        static int networkProtocol;\n        static std::string networkCommitHash;'''
    t = replace_once(t, anchor, add, 'Main.hpp static state')
save(rel, t)

rel = 'apps/openmw/mwmp/Networking.cpp'
t = load(rel)
if 'const std::string advertisedVersion = Main::getNetworkVersion();' not in t:
    t = replace_once(t, '    std::stringstream sstr;\n    sstr << TES3MP_VERSION;',
        '    std::stringstream sstr;\n    const std::string advertisedVersion = Main::getNetworkVersion();\n    sstr << advertisedVersion;',
        'Networking.cpp advertised version')

# Replace the X031 fixed identity block. Anchor is the explanatory comment + three named
# variables/macros, so line movement around connect() is harmless.
if 'Main::useVanillaBuildServer()' not in t:
    old = '''    // X031: ArenaMP no longer impersonates the vanilla TES3MP build identity.\n    // Every client advertises the ArenaMP protocol and stable compatibility hash.\n    const int advertisedProtocol = TES3MP_PROTO_VERSION;\n    sstr << advertisedProtocol;\n\n    std::string commitHashString = TES3MP_COMPAT_COMMIT_HASH;'''
    new = '''    // Android may explicitly request an older server identity; otherwise use the\n    // ArenaMP/Y001 stable identity or optional manifest overrides.\n    const int advertisedProtocol = Main::useVanillaBuildServer()\n        ? TES3MP_VANILLA_PROTO_VERSION : Main::getNetworkProtocol();\n    sstr << advertisedProtocol;\n\n    std::string commitHashString;\n    if (Main::useVanillaBuildServer())\n        commitHashString = TES3MP_VANILLA_COMMIT_HASH;\n    else if (!Main::getNetworkCommitHash().empty())\n        commitHashString = Main::getNetworkCommitHash();\n    else\n        commitHashString = TES3MP_COMPAT_COMMIT_HASH;'''
    t = replace_once(t, old, new, 'Networking.cpp identity block')

if 'Your client advertises ArenaMP/TES3MP version' not in t:
    old = '''                    errmsg = "Version mismatch!\\nYour client is on version " TES3MP_VERSION "\\n"\n                        "Please make sure the server is on the same version.";'''
    new = '''                    errmsg = "Version mismatch!\\nYour client advertises ArenaMP/TES3MP version " + advertisedVersion + "\\n"\n                        "Please make sure the network compatibility identity matches the server.";'''
    t = replace_once(t, old, new, 'Networking.cpp mismatch message')
save(rel, t)
print(f'ArenaMP network identity overrides applied by semantic anchors; compatibility commit={compat}')
