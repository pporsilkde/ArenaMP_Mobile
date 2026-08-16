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

    anchor = 'set_target_properties(tes3mp-server PROPERTIES\n'
    idx = text.find(anchor)
    if idx < 0:
        raise SystemExit('Could not locate tes3mp-server target properties')
    block = re.search(r'set_target_properties\(tes3mp-server PROPERTIES\s*.*?\n\)', text[idx:], re.S)
    if not block:
        raise SystemExit('Could not parse tes3mp-server target properties block')
    pos = idx + block.end()
    text = text[:pos] + '''

if(ANDROID)
    set_target_properties(tes3mp-server PROPERTIES OUTPUT_NAME "arenamp_server")
endif()
''' + text[pos:]
    cmake.write_text(text, encoding='utf-8')

# Expose the dedicated server's main body to JNI, while preserving main() on PC.
src = main.read_text(encoding='utf-8')
if 'tes3mpServerMain' not in src:
    old = 'int main(int argc, char *argv[])'
    if old not in src:
        raise SystemExit('Could not locate tes3mp-server main()')
    new = '''#ifdef __ANDROID__
int tes3mpServerMain(int argc, char *argv[])
#else
int main(int argc, char *argv[])
#endif'''
    src = src.replace(old, new, 1)

# The PC launcher restarts a fresh process. Android owns the server inside a
# foreground Service process, so make a failed run unwind cleanly enough to be
# re-entered: don't rethrow past JNI and release loaded Lua scripts.
if 'ARENAMP_ANDROID_SERVER_RESTART_CLEANUP' not in src:
    throw_old = '        Script::Call<Script::CallbackIdentity("OnServerScriptCrash")>(e.what());\n        throw; //fall through'
    if throw_old not in src:
        raise SystemExit('Could not locate dedicated-server exception rethrow')
    throw_new = '''        Script::Call<Script::CallbackIdentity("OnServerScriptCrash")>(e.what());
#ifdef __ANDROID__
        // ARENAMP_ANDROID_SERVER_RESTART_CLEANUP
        code = 125;
#else
        throw; // desktop process supervisor performs a clean restart
#endif'''
    src = src.replace(throw_old, throw_new, 1)

    # The upstream server calls getMasterClient()->Stop() unconditionally even
    # when [MasterServer] enabled=false (the shipped default). A desktop process
    # exits immediately afterwards, but Android needs a clean return to JNI for
    # Service-managed stop/restart. Guard the null pointer in the Android build.
    master_stop_old = '        networking.getMasterClient()->Stop();'
    if master_stop_old in src and 'ARENAMP_ANDROID_MASTER_STOP_GUARD' not in src:
        master_stop_new = '''#ifdef __ANDROID__
        // ARENAMP_ANDROID_MASTER_STOP_GUARD
        if (networking.getMasterClient())
            networking.getMasterClient()->Stop();
#else
        networking.getMasterClient()->Stop();
#endif'''
        src = src.replace(master_stop_old, master_stop_new, 1)

    cleanup_old = '    breakpad_close();\n    return code;'
    if cleanup_old not in src:
        raise SystemExit('Could not locate dedicated-server return cleanup')
    cleanup_new = '''#ifdef __ANDROID__
    Script::UnloadScripts();
#endif
    breakpad_close();
    return code;'''
    src = src.replace(cleanup_old, cleanup_new, 1)

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
    old_functions = '''    static constexpr ScriptFunctionData functions[]{
            {"CreateTimer",         ScriptFunctions::CreateTimer},
            {"CreateTimerEx",       ScriptFunctions::CreateTimerEx},
            {"MakePublic",          ScriptFunctions::MakePublic},
            {"CallPublic",          ScriptFunctions::CallPublic},
'''
    new_functions = '''    static constexpr ScriptFunctionData functions[]{
            {"CreateTimer",         ScriptFunctions::CreateTimer},
#ifndef __ANDROID__
            // ARENAMP_ANDROID_VA_LIST_NATIVE_TABLE: AArch64 va_list is not a pointer.
            {"CreateTimerEx",       ScriptFunctions::CreateTimerEx},
#endif
            {"MakePublic",          ScriptFunctions::MakePublic},
#ifndef __ANDROID__
            {"CallPublic",          ScriptFunctions::CallPublic},
#endif
'''
    if old_functions not in sf:
        raise SystemExit('Could not locate ScriptFunctions::functions va_list entries')
    sf = sf.replace(old_functions, new_functions, 1)

    old_addresses = '''    inline static const ScriptFunctionAddress functionAddresses[]{
            {"CreateTimer",         ScriptFunctions::CreateTimer},
            {"CreateTimerEx",       ScriptFunctions::CreateTimerEx},
            {"MakePublic",          ScriptFunctions::MakePublic},
            {"CallPublic",          ScriptFunctions::CallPublic},
'''
    new_addresses = '''    inline static const ScriptFunctionAddress functionAddresses[]{
            {"CreateTimer",         ScriptFunctions::CreateTimer},
#ifndef __ANDROID__
            {"CreateTimerEx",       ScriptFunctions::CreateTimerEx},
#endif
            {"MakePublic",          ScriptFunctions::MakePublic},
#ifndef __ANDROID__
            {"CallPublic",          ScriptFunctions::CallPublic},
#endif
'''
    if old_addresses not in sf:
        raise SystemExit('Could not locate ScriptFunctions::functionAddresses va_list entries')
    sf = sf.replace(old_addresses, new_addresses, 1)
    script_functions.write_text(sf, encoding='utf-8')

# Android server portable storage override. The Service sets the environment
# variable before tes3mpServerMain(), so only the server uses ArenaMP/config.
cm = config_manager.read_text(encoding='utf-8')
if 'ARENAMP_ANDROID_SERVER_CONFIG_ROOT' not in cm:
    old_cfg_paths = """        mLocalPath = mFixedPath.getLocalPath();
        mUserConfigPath = mLocalPath / \"userdata\";
        mUserDataPath = mUserConfigPath;

        if (!ensureDirectory(mUserConfigPath) || !ensureDirectory(mUserDataPath))
        {
            mUserConfigPath = mFixedPath.getUserConfigPath();
            mUserDataPath = mFixedPath.getUserDataPath();
            ensureDirectory(mUserConfigPath);
            ensureDirectory(mUserDataPath);
        }
"""
    new_cfg_paths = """        mLocalPath = mFixedPath.getLocalPath();
#if defined(__ANDROID__)
        // ARENAMP_ANDROID_SERVER_CONFIG_ROOT
        if (const char* serverRoot = std::getenv(\"ARENAMP_ANDROID_SERVER_ROOT\"))
        {
            mUserConfigPath = boost::filesystem::path(serverRoot) / \"config\";
            mUserDataPath = boost::filesystem::path(serverRoot);
            ensureDirectory(mUserConfigPath);
            ensureDirectory(mUserDataPath);
        }
        else
#endif
        {
            mUserConfigPath = mLocalPath / \"userdata\";
            mUserDataPath = mUserConfigPath;

            if (!ensureDirectory(mUserConfigPath) || !ensureDirectory(mUserDataPath))
            {
                mUserConfigPath = mFixedPath.getUserConfigPath();
                mUserDataPath = mFixedPath.getUserDataPath();
                ensureDirectory(mUserConfigPath);
                ensureDirectory(mUserDataPath);
            }
        }
"""
    if old_cfg_paths not in cm:
        raise SystemExit('Could not locate ConfigurationManager local userdata preference')
    cm = cm.replace(old_cfg_paths, new_cfg_paths, 1)
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
        r"(?m)^(?P<indent>\s*)if \(kbhit\(\) && getch\(\) == '\\n'\)\s*\n(?P=indent)\s+break;"
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
