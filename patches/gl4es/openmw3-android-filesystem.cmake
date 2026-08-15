if(NOT DEFINED SOURCE_DIR)
    message(FATAL_ERROR "SOURCE_DIR is required")
endif()

set(_file "${SOURCE_DIR}/3rdparty/glslang/glslang/Include/InfoSink.h")
if(NOT EXISTS "${_file}")
    message(FATAL_ERROR "glslang InfoSink.h not found: ${_file}")
endif()

file(READ "${_file}" _src)
set(_old1 [=[        if(loc.getFilename() == nullptr && shaderFileName != nullptr && absolute) {
            append(std::filesystem::absolute(shaderFileName).string());
        } else {
            std::string location = loc.getStringNameOrNum(false);
            if (absolute) {
                append(std::filesystem::absolute(location).string());
            } else {
                append(location);
            }
        }
]=])
set(_new1 [=[        if(loc.getFilename() == nullptr && shaderFileName != nullptr && absolute) {
#if defined(__ANDROID__)
            // Android NDK r21e exposes <filesystem> declarations but does not
            // provide the std::filesystem implementation required by this
            // newer glslang snapshot. Absolute paths are only cosmetic here:
            // this function formats shader diagnostics and does not affect
            // parsing, SPIR-V generation, or shader semantics.
            append(shaderFileName);
#else
            append(std::filesystem::absolute(shaderFileName).string());
#endif
        } else {
            std::string location = loc.getStringNameOrNum(false);
            if (absolute) {
#if defined(__ANDROID__)
                append(location);
#else
                append(std::filesystem::absolute(location).string());
#endif
            } else {
                append(location);
            }
        }
]=])

string(FIND "${_src}" "Android NDK r21e exposes <filesystem> declarations" _already)
if(NOT _already EQUAL -1)
    message(STATUS "ArenaMW: glslang Android filesystem compatibility patch already applied")
else()
    string(FIND "${_src}" "std::filesystem::absolute(shaderFileName).string()" _needs_patch)
    if(NOT _needs_patch EQUAL -1)
        string(REPLACE "${_old1}" "${_new1}" _patched "${_src}")
        if(_patched STREQUAL _src)
            message(FATAL_ERROR "ArenaMW: glslang filesystem block changed upstream; refusing an unsafe blind patch")
        endif()
        file(WRITE "${_file}" "${_patched}")
        message(STATUS "ArenaMW: disabled diagnostic-only std::filesystem::absolute on Android NDK r21e")
    else()
        message(WARNING "ArenaMW: glslang no longer uses filesystem::absolute in InfoSink; compatibility patch not needed")
    endif()
endif()
