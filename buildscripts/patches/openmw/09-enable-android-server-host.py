#!/usr/bin/env python3
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1]).resolve()
cmake = root / 'apps/openmw-mp/CMakeLists.txt'
main = root / 'apps/openmw-mp/main.cpp'
networking = root / 'apps/openmw-mp/Networking.cpp'
script_functions = root / 'apps/openmw-mp/Script/ScriptFunctions.hpp'
config_manager = root / 'components/files/configurationmanager.cpp'
if not cmake.is_file() or not main.is_file() or not networking.is_file() or not script_functions.is_file() or not config_manager.is_file():
    raise SystemExit('ArenaMP server sources not found')

# Turn only the Android dedicated-server target into a shared library. Desktop
# keeps the exact tes3mp-server executable target/behavior.
text = cmake.read_text(encoding='utf-8')
if 'libarenamp_server Android host' not in text:
    pat = re.compile(r'add_executable\(tes3mp-server\s*\n(?P<body>.*?)\n\s*\)', re.S)
    m = pat.search(text)
    if not m:
        raise SystemExit('Could not locate add_executable(tes3mp-server)')
    body = m.group('body')
    replacement = '''# libarenamp_server Android host: keep the desktop executable unchanged,
# but expose the dedicated server as a shared library on Android so a
# foreground Service in a separate app process can own its lifetime.
if(ANDROID)
    add_library(tes3mp-server SHARED
%s
        android_server_jni.cpp
        )
else()
    add_executable(tes3mp-server
%s
        )
endif()''' % (body, body)
    text = text[:m.start()] + replacement + text[m.end():]

    block = re.search(
        r'set_target_properties\s*\(\s*tes3mp-server\s+PROPERTIES\b.*?\n\s*\)', text, re.S
    )
    if not block:
        raise SystemExit('Could not locate tes3mp-server target properties by target name')
    pos = block.end()
    text = text[:pos] + '''

if(ANDROID)
    set_target_properties(tes3mp-server PROPERTIES OUTPUT_NAME "arenamp_server")
endif()
''' + text[pos:]
    cmake.write_text(text, encoding='utf-8')

# Expose the dedicated server's main body to JNI, while preserving main() on PC.
src = main.read_text(encoding='utf-8')
if 'tes3mpServerMain' not in src:
    main_pattern = re.compile(
        r'(?m)^int\s+main\s*\(\s*int\s+argc\s*,\s*char\s*\*\s*argv\s*\[\s*\]\s*\)\s*$'
    )
    m = main_pattern.search(src)
    if not m:
        raise SystemExit('Could not locate tes3mp-server main() by signature')
    new = '''#ifdef __ANDROID__
int tes3mpServerMain(int argc, char *argv[])
#else
int main(int argc, char *argv[])
#endif'''
    src = src[:m.start()] + new + src[m.end():]

# The PC launcher restarts a fresh process. Android owns the server inside a
# foreground Service process, so make a failed run unwind cleanly enough to be
# re-entered. These edits intentionally use semantic anchors rather than a
# byte-for-byte tail of main(): desktop Y050/Y052 added restart code between
# breakpad_close() and the final return, which made the old patch brittle.
if 'ARENAMP_ANDROID_SERVER_RESTART_CLEANUP' not in src:
    crash_call = re.compile(
        r'(?m)^(?P<indent>[ \t]*)Script::Call<Script::CallbackIdentity\("OnServerScriptCrash"\)>\(e\.what\(\)\);\s*$'
    )
    m = crash_call.search(src)
    if not m:
        raise SystemExit('Could not locate OnServerScriptCrash callback')
    tail = src[m.end():m.end() + 320]
    throw_match = re.search(r'(?m)^(?P<indent>[ \t]*)throw\s*;[^\n]*$', tail)
    if not throw_match:
        raise SystemExit('Could not locate exception rethrow after OnServerScriptCrash')
    abs_start = m.end() + throw_match.start()
    abs_end = m.end() + throw_match.end()
    indent = throw_match.group('indent')
    replacement = (
        f'{indent}#ifdef __ANDROID__\n'
        f'{indent}// ARENAMP_ANDROID_SERVER_RESTART_CLEANUP\n'
        f'{indent}code = 125;\n'
        f'{indent}#else\n'
        f'{indent}throw; // desktop process supervisor performs a clean restart\n'
        f'{indent}#endif'
    )
    src = src[:abs_start] + replacement + src[abs_end:]

# Older TES3MP branches stopped the master client unconditionally. Newer AMP
# already has a general nullptr guard (Y052). Only add an Android guard when
# neither form exists, avoiding nested/duplicate preprocessor blocks.
if 'ARENAMP_ANDROID_MASTER_STOP_GUARD' not in src:
    stop_line = re.compile(r'(?m)^(?P<indent>[ \t]*)networking\.getMasterClient\(\)->Stop\(\);\s*$')
    matches = list(stop_line.finditer(src))
    if len(matches) == 1:
        m = matches[0]
        context = src[max(0, m.start() - 220):m.start()]
        already_null_guarded = re.search(
            r'if\s*\(\s*networking\.getMasterClient\(\)\s*!=\s*nullptr\s*\)\s*$',
            context,
            re.M,
        ) is not None or re.search(
            r'if\s*\(\s*networking\.getMasterClient\(\)\s*\)\s*$', context, re.M
        ) is not None
        if not already_null_guarded:
            indent = m.group('indent')
            replacement = (
                f'{indent}#ifdef __ANDROID__\n'
                f'{indent}// ARENAMP_ANDROID_MASTER_STOP_GUARD\n'
                f'{indent}if (networking.getMasterClient())\n'
                f'{indent}    networking.getMasterClient()->Stop();\n'
                f'{indent}#else\n'
                f'{indent}networking.getMasterClient()->Stop();\n'
                f'{indent}#endif'
            )
            src = src[:m.start()] + replacement + src[m.end():]
    elif len(matches) > 1:
        raise SystemExit(f'Ambiguous master-client Stop() anchors: found {len(matches)}')

# Release Lua scripts immediately before the final breakpad close. This is a
# stable semantic boundary even when AMP inserts logging/restart logic before
# or after it. Match the call line, not the breakpad_close() function body.
if 'ARENAMP_ANDROID_LUA_UNLOAD_BEFORE_BREAKPAD' not in src:
    close_call = re.compile(r'(?m)^(?P<indent>[ \t]*)breakpad_close\(\);\s*$')
    matches = list(close_call.finditer(src))
    if len(matches) != 1:
        raise SystemExit(f'Could not uniquely locate final breakpad_close() call; found {len(matches)}')
    m = matches[0]
    indent = m.group('indent')
    insertion = (
        f'{indent}#ifdef __ANDROID__\n'
        f'{indent}// ARENAMP_ANDROID_LUA_UNLOAD_BEFORE_BREAKPAD\n'
        f'{indent}Script::UnloadScripts();\n'
        f'{indent}#endif\n'
    )
    src = src[:m.start()] + insertion + src[m.start():]

# Y050+ performs a desktop self-relaunch on reserved exit code 42. Android must
# never fork/exec from the shared server library: return the code to the
# foreground Service, which owns process lifetime and restarts nativeRun().
if 'sArenaEmbeddedRestartExitCode' in src and 'ARENAMP_ANDROID_SERVICE_RESTART_CODE' not in src:
    restart_if = re.compile(
        r'(?m)^(?P<indent>[ \t]*)if\s*\(\s*code\s*==\s*sArenaEmbeddedRestartExitCode\s*\)\s*$'
    )
    matches = list(restart_if.finditer(src))
    if len(matches) != 1:
        raise SystemExit(f'Could not uniquely locate embedded restart dispatch; found {len(matches)}')
    m = matches[0]
    indent = m.group('indent')
    insertion = (
        f'{indent}#ifdef __ANDROID__\n'
        f'{indent}// ARENAMP_ANDROID_SERVICE_RESTART_CODE\n'
        f'{indent}if (code == sArenaEmbeddedRestartExitCode)\n'
        f'{indent}    return sArenaEmbeddedRestartExitCode;\n'
        f'{indent}#endif\n'
    )
    src = src[:m.start()] + insertion + src[m.start():]

main.write_text(src, encoding='utf-8')

# Android/AArch64 defines va_list as an ABI structure (__va_list, currently 32
# bytes), not as a pointer. The legacy native-script signature table tries to
# encode every argument through TypeChar<T, sizeof(T)> and therefore rejects
# CreateTimerEx/CallPublic at compile time on arm64. Lua does not use this table
# for these functions: LangLua registers CreateTimerEx and CallPublic separately
# as lua_CFunction bindings, so keep the Lua API intact and omit only the two
# va_list entries from the optional native C++ script export table on Android.
sf = script_functions.read_text(encoding='utf-8')
if 'ARENAMP_ANDROID_VA_LIST_NATIVE_TABLE' not in sf:
    def guard_table_entries(text, table_name, add_marker=False):
        table_re = re.compile(
            rf'(?P<head>\b{re.escape(table_name)}\s*\[\s*\]\s*\{{)(?P<body>.*?)(?P<tail>\n\s*\}};)',
            re.S,
        )
        matches = list(table_re.finditer(text))
        if len(matches) != 1:
            raise SystemExit(f'Could not uniquely locate ScriptFunctions::{table_name} table; found {len(matches)}')
        m = matches[0]
        body = m.group('body')
        for index, (api_name, symbol) in enumerate((
            ('CreateTimerEx', 'ScriptFunctions::CreateTimerEx'),
            ('CallPublic', 'ScriptFunctions::CallPublic'),
        )):
            entry_re = re.compile(
                rf'(?m)^(?P<indent>[ \t]*)\{{\s*"{api_name}"\s*,\s*{re.escape(symbol)}\s*\}},\s*$'
            )
            entries = list(entry_re.finditer(body))
            if len(entries) != 1:
                raise SystemExit(
                    f'{table_name}: could not uniquely locate {api_name} entry; found {len(entries)}'
                )
            e = entries[0]
            indent = e.group('indent')
            marker = ''
            if add_marker and index == 0:
                marker = f'{indent}// ARENAMP_ANDROID_VA_LIST_NATIVE_TABLE\n'
            replacement = (
                f'{marker}{indent}#ifndef __ANDROID__\n'
                f'{e.group(0)}\n'
                f'{indent}#endif'
            )
            body = body[:e.start()] + replacement + body[e.end():]
        return text[:m.start('body')] + body + text[m.end('body'):]

    sf = guard_table_entries(sf, 'functions', add_marker=True)
    sf = guard_table_entries(sf, 'functionAddresses')
    script_functions.write_text(sf, encoding='utf-8')

# Android server portable storage override. The Service sets the environment
# variable before tes3mpServerMain(), so only the server uses ArenaMP/config.
cm = config_manager.read_text(encoding='utf-8')
if 'ARENAMP_ANDROID_SERVER_CONFIG_ROOT' not in cm:
    # Match the constructor's local/userdata policy by the assignments it
    # performs, not by exact blank lines/comments. This survives formatting
    # and nearby-path-policy changes while still requiring one unambiguous
    # constructor block.
    cfg_pattern = re.compile(
        r'''(?mx)
        ^(?P<indent>[ \t]*)mLocalPath\s*=\s*mFixedPath\.getLocalPath\(\);[ \t]*\n
        (?P=indent)mUserConfigPath\s*=\s*mLocalPath\s*/\s*"userdata";[ \t]*\n
        (?P=indent)mUserDataPath\s*=\s*mUserConfigPath;[ \t]*\n
        (?:[ \t]*\n)?
        (?P=indent)if\s*\(\s*!ensureDirectory\(mUserConfigPath\)\s*\|\|\s*!ensureDirectory\(mUserDataPath\)\s*\)[ \t]*\n
        (?P=indent)\{[ \t]*\n
        (?P=indent)[ \t]+mUserConfigPath\s*=\s*mFixedPath\.getUserConfigPath\(\);[ \t]*\n
        (?P=indent)[ \t]+mUserDataPath\s*=\s*mFixedPath\.getUserDataPath\(\);[ \t]*\n
        (?P=indent)[ \t]+ensureDirectory\(mUserConfigPath\);[ \t]*\n
        (?P=indent)[ \t]+ensureDirectory\(mUserDataPath\);[ \t]*\n
        (?P=indent)\}[ \t]*\n?
        '''
    )
    matches = list(cfg_pattern.finditer(cm))
    if len(matches) != 1:
        raise SystemExit(
            f'Could not uniquely locate ConfigurationManager local userdata policy; found {len(matches)}'
        )
    m = matches[0]
    indent = m.group('indent')
    inner = indent + '    '
    replacement = (
        f'{indent}mLocalPath = mFixedPath.getLocalPath();\n'
        f'#if defined(__ANDROID__)\n'
        f'{indent}// ARENAMP_ANDROID_SERVER_CONFIG_ROOT\n'
        f'{indent}if (const char* serverRoot = std::getenv("ARENAMP_ANDROID_SERVER_ROOT"))\n'
        f'{indent}{{\n'
        f'{inner}mUserConfigPath = boost::filesystem::path(serverRoot) / "config";\n'
        f'{inner}mUserDataPath = boost::filesystem::path(serverRoot);\n'
        f'{inner}ensureDirectory(mUserConfigPath);\n'
        f'{inner}ensureDirectory(mUserDataPath);\n'
        f'{indent}}}\n'
        f'{indent}else\n'
        f'#endif\n'
        f'{indent}{{\n'
        f'{inner}mUserConfigPath = mLocalPath / "userdata";\n'
        f'{inner}mUserDataPath = mUserConfigPath;\n'
        f'{inner}if (!ensureDirectory(mUserConfigPath) || !ensureDirectory(mUserDataPath))\n'
        f'{inner}{{\n'
        f'{inner}    mUserConfigPath = mFixedPath.getUserConfigPath();\n'
        f'{inner}    mUserDataPath = mFixedPath.getUserDataPath();\n'
        f'{inner}    ensureDirectory(mUserConfigPath);\n'
        f'{inner}    ensureDirectory(mUserDataPath);\n'
        f'{inner}}}\n'
        f'{indent}}}\n'
    )
    cm = cm[:m.start()] + replacement + cm[m.end():]
    config_manager.write_text(cm, encoding='utf-8')

# A foreground Service has no terminal. RakNet's legacy Kbhit helper attempts
# tcgetattr()/select() on stdin and can touch invalid termios state when fd 0 is
# not a TTY. Preserve the desktop console shortcut and skip stdin on Android.
net = networking.read_text(encoding='utf-8')

if 'ARENAMP_ANDROID_LOOP_RESET' not in net:
    loop_head = 'int Networking::mainLoop()\n{'
    if loop_head not in net:
        raise SystemExit('Could not locate Networking::mainLoop()')
    net = net.replace(loop_head, '''int Networking::mainLoop()
{
#ifdef __ANDROID__
    // ARENAMP_ANDROID_LOOP_RESET: allow Service-managed restart after a prior signal.
    killLoop = false;
#endif''', 1)

if 'ARENAMP_ANDROID_NO_STDIN' not in net:
    include_old = '#include <Kbhit.h>'
    if include_old not in net:
        raise SystemExit('Could not locate Kbhit include in Networking.cpp')
    include_new = '''#ifndef __ANDROID__
#include <Kbhit.h>
#else
#define ARENAMP_ANDROID_NO_STDIN 1
#endif'''
    net = net.replace(include_old, include_new, 1)

    loop_pattern = re.compile(
        r"(?m)^(?P<indent>[ \t]*)if \(kbhit\(\) && getch\(\) == '\\n'\)[ \t]*\n(?P=indent)[ \t]+break;"
    )
    m = loop_pattern.search(net)
    if not m:
        raise SystemExit('Could not locate server stdin polling in Networking.cpp')
    indent = m.group('indent')
    loop_new = (
        f'{indent}#ifndef __ANDROID__\n'
        f"{indent}if (kbhit() && getch() == '\\n')\n"
        f'{indent}    break;\n'
        f'{indent}#endif'
    )
    net = net[:m.start()] + loop_new + net[m.end():]

networking.write_text(net, encoding='utf-8')
print('ArenaMP Android dedicated-server host patch applied')
