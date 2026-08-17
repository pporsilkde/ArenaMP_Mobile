#!/usr/bin/env python3
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


def load(rel: str) -> str:
    p = root / rel
    if not p.is_file():
        raise SystemExit(f'missing required source file: {rel}')
    return p.read_text(encoding='utf-8')


def save(rel: str, text: str) -> None:
    (root / rel).write_text(text, encoding='utf-8')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one anchor, found {count}')
    return text.replace(old, new, 1)


# 1) Build-time compatibility identity shared by both client and dedicated server.
version_rel = 'components/openmw-mp/Version.hpp'
text = load(version_rel)
text, n = re.subn(
    r'(#define\s+TES3MP_COMPAT_COMMIT_HASH\s+")[0-9a-fA-F]{40}("\s*)',
    rf'\g<1>{compat}\2',
    text,
    count=1,
)
if n != 1:
    raise SystemExit('Version.hpp: TES3MP_COMPAT_COMMIT_HASH was not found exactly once')
save(version_rel, text)

# 2) Client command-line overrides used by Android build.ini for remote servers.
main_cpp_rel = 'apps/openmw/mwmp/Main.cpp'
text = load(main_cpp_rel)
text = replace_once(
    text,
    'bool Main::vanillaBuildServer = false;\nbool Main::hideChatHistory = false;',
    'bool Main::vanillaBuildServer = false;\nstd::string Main::networkVersion = "";\nint Main::networkProtocol = 0;\nstd::string Main::networkCommitHash = "";\nbool Main::hideChatHistory = false;',
    'Main.cpp statics',
)
text = replace_once(
    text,
    'bool Main::useVanillaBuildServer()\n{\n    return vanillaBuildServer;\n}\n\nbool Main::isChatHistoryHidden()',
    'bool Main::useVanillaBuildServer()\n{\n    return vanillaBuildServer;\n}\n\nstd::string Main::getNetworkVersion()\n{\n    return networkVersion.empty() ? TES3MP_VERSION : networkVersion;\n}\n\nint Main::getNetworkProtocol()\n{\n    return networkProtocol > 0 ? networkProtocol : TES3MP_PROTO_VERSION;\n}\n\nstd::string Main::getNetworkCommitHash()\n{\n    return networkCommitHash;\n}\n\nbool Main::isChatHistoryHidden()',
    'Main.cpp getters',
)
text = replace_once(
    text,
    '            ("vanilla-build-server", bpo::value<bool>()->implicit_value(true)->default_value(false),\n                        "use the official TES3MP 0.8.1 commit identity for older servers")\n            ("hide-chat-history", bpo::value<bool>()->implicit_value(true)->default_value(false),',
    '            ("vanilla-build-server", bpo::value<bool>()->implicit_value(true)->default_value(false),\n                        "use the official TES3MP 0.8.1 commit identity for older servers")\n            ("network-version", bpo::value<std::string>()->default_value(""),\n                        "override the ArenaMP/TES3MP version advertised during the RakNet handshake")\n            ("network-protocol", bpo::value<int>()->default_value(0),\n                        "override the ArenaMP/TES3MP protocol advertised during the RakNet handshake")\n            ("network-commit-hash", bpo::value<std::string>()->default_value(""),\n                        "override the ArenaMP parent compatibility commit advertised during the RakNet handshake")\n            ("hide-chat-history", bpo::value<bool>()->implicit_value(true)->default_value(false),',
    'Main.cpp options',
)
text = replace_once(
    text,
    '    Main::vanillaBuildServer = variables["vanilla-build-server"].as<bool>();\n    Main::hideChatHistory = variables["hide-chat-history"].as<bool>();',
    '    Main::vanillaBuildServer = variables["vanilla-build-server"].as<bool>();\n    Main::networkVersion = variables["network-version"].as<std::string>();\n    Main::networkProtocol = variables["network-protocol"].as<int>();\n    Main::networkCommitHash = variables["network-commit-hash"].as<std::string>();\n    Main::hideChatHistory = variables["hide-chat-history"].as<bool>();',
    'Main.cpp configure',
)
save(main_cpp_rel, text)

main_hpp_rel = 'apps/openmw/mwmp/Main.hpp'
text = load(main_hpp_rel)
text = replace_once(
    text,
    '        static std::string getResDir();\n        static bool useVanillaBuildServer();\n        static bool isChatHistoryHidden();',
    '        static std::string getResDir();\n        static bool useVanillaBuildServer();\n        static std::string getNetworkVersion();\n        static int getNetworkProtocol();\n        static std::string getNetworkCommitHash();\n        static bool isChatHistoryHidden();',
    'Main.hpp getters',
)
text = replace_once(
    text,
    '        static std::string serverPassword;\n        static bool vanillaBuildServer;\n        static bool hideChatHistory;',
    '        static std::string serverPassword;\n        static bool vanillaBuildServer;\n        static std::string networkVersion;\n        static int networkProtocol;\n        static std::string networkCommitHash;\n        static bool hideChatHistory;',
    'Main.hpp statics',
)
save(main_hpp_rel, text)

network_rel = 'apps/openmw/mwmp/Networking.cpp'
text = load(network_rel)
text = replace_once(
    text,
    '    std::stringstream sstr;\n    sstr << TES3MP_VERSION;',
    '    std::stringstream sstr;\n    const std::string advertisedVersion = Main::getNetworkVersion();\n    sstr << advertisedVersion;',
    'Networking.cpp version',
)

# Only replace the non-vanilla branch of the protocol selection.
if ': Main::getNetworkProtocol();' not in text:
    proto_pattern = re.compile(
        r'(const int advertisedProtocol = Main::useVanillaBuildServer\(\)\s*\n\s*\? TES3MP_VANILLA_PROTO_VERSION\s*\n\s*:) TES3MP_PROTO_VERSION;'
    )
    text, n = proto_pattern.subn(r'\1 Main::getNetworkProtocol();', text, count=1)
    if n != 1:
        raise SystemExit('Networking.cpp protocol: compatible anchor not found')

if 'Parent ArenaMP compatibility: advertising version' not in text:
    # Current AMP/main already has a stable TES3MP_COMPAT_COMMIT_HASH fallback.
    current_fallback = '''    else\n    {\n        // ArenaMP uses a stable compatibility identity instead of the current\n        // source Git HEAD, so independently rebuilt client/server stay compatible.\n        commitHashString = TES3MP_COMPAT_COMMIT_HASH;\n    }'''
    if current_fallback in text:
        replacement = '''    else if (!Main::getNetworkCommitHash().empty())\n    {\n        commitHashString = Main::getNetworkCommitHash();\n        LOG_MESSAGE_SIMPLE(TimedLog::LOG_INFO,\n            "Parent ArenaMP compatibility: advertising version %s, protocol %i, commit %.10s",\n            advertisedVersion.c_str(), advertisedProtocol, commitHashString.c_str());\n    }\n    else\n    {\n        // Stable build-time identity shared with the bundled dedicated server.\n        commitHashString = TES3MP_COMPAT_COMMIT_HASH;\n    }'''
        text = text.replace(current_fallback, replacement, 1)
    else:
        # Compatibility with older AMP bases that still read resources/version.
        older = '    else\n        commitHashString = Version::getOpenmwVersion(Main::getResDir()).mCommitHash;'
        if older not in text:
            raise SystemExit('Networking.cpp commit fallback: compatible anchor not found')
        replacement = '''    else if (!Main::getNetworkCommitHash().empty())\n    {\n        commitHashString = Main::getNetworkCommitHash();\n        LOG_MESSAGE_SIMPLE(TimedLog::LOG_INFO,\n            "Parent ArenaMP compatibility: advertising version %s, protocol %i, commit %.10s",\n            advertisedVersion.c_str(), advertisedProtocol, commitHashString.c_str());\n    }\n    else\n        commitHashString = Version::getOpenmwVersion(Main::getResDir()).mCommitHash;'''
        text = text.replace(older, replacement, 1)

if 'Your client advertises ArenaMP/TES3MP version' not in text:
    mismatch_pattern = re.compile(
        r'(?P<indent>\s*)errmsg = "Version mismatch!\\nYour client is on version " TES3MP_VERSION "\\n"\s*\n\s*"Please make sure the server is on the same version\.";'
    )
    def _mismatch_repl(match):
        indent = match.group('indent')
        return (indent + 'errmsg = "Version mismatch!\\nYour client advertises ArenaMP/TES3MP version " + advertisedVersion + "\\n"\n'
                + indent + '    "Please make sure the network compatibility identity matches the server.";')
    text, n = mismatch_pattern.subn(_mismatch_repl, text, count=1)
    if n != 1:
        raise SystemExit('Networking.cpp mismatch message: compatible anchor not found')

save(network_rel, text)
print(f'ArenaMP network identity patch applied; compatibility commit={compat}')
