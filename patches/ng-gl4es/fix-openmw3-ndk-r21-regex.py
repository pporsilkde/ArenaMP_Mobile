#!/usr/bin/env python3
"""Make Sisah2/NG-GL4ES Openmw3 compile with Android NDK r21e libc++.

Two narrow compatibility fixes are applied:
1. Openmw3 uses std::regex::multiline in glsl_for_es.cpp, which r21e libc++
   does not expose. Preserve the same line-oriented match semantics explicitly.
2. The pinned glslang uses std::filesystem::absolute() only to prettify shader
   diagnostic paths. r21e exposes the header but does not resolve the required
   filesystem implementation in this shared-library link. On Android keep the
   original path instead; shader compilation behavior is unchanged.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: fix-openmw3-ndk-r21-regex.py <NG-GL4ES source dir>")

source_dir = Path(sys.argv[1])

# 1) std::regex::multiline compatibility in Openmw3 shader conversion.
path = source_dir / "src/gl/glsl/glsl_for_es.cpp"
text = path.read_text(encoding="utf-8")
old = '''    const std::regex uniformRegex(\n        R"(^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+\\w+(?:\\s*\\[\\s*\\d+\\s*\\])?\\s+\\w+(?:\\s*\\[\\s*\\d+\\s*\\])?\\s*;.*$)",\n        std::regex::ECMAScript | std::regex::multiline);'''
new = '''    // Android NDK r21e libc++ does not provide std::regex::multiline.\n    // Match a complete uniform declaration line explicitly instead.\n    const std::regex uniformRegex(\n        R"((?:^|\\n)[ \\t]*(?:layout[ \\t]*\\([^\\r\\n)]*\\)[ \\t]*)?uniform[ \\t]+\\w+(?:[ \\t]*\\[[ \\t]*\\d+[ \\t]*\\])?[ \\t]+\\w+(?:[ \\t]*\\[[ \\t]*\\d+[ \\t]*\\])?[ \\t]*;[^\\r\\n]*)",\n        std::regex::ECMAScript);'''

if old in text:
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("NG-GL4ES regex compatibility: replaced std::regex::multiline for NDK r21e")
elif "std::regex::ECMAScript | std::regex::multiline" in text:
    raise SystemExit(
        "NG-GL4ES regex compatibility: multiline flag is present, "
        "but the expected Openmw3 block changed; refusing a blind patch"
    )
else:
    print("NG-GL4ES regex compatibility: upstream/already patched")

# 2) glslang filesystem diagnostic compatibility.
info = source_dir / "3rdparty/glslang/glslang/Include/InfoSink.h"
info_text = info.read_text(encoding="utf-8")

plain_include = '#include <filesystem>'
guarded_include = '''#if !defined(__ANDROID__)\n#include <filesystem>\n#endif'''
if guarded_include in info_text:
    pass
elif plain_include in info_text:
    info_text = info_text.replace(plain_include, guarded_include, 1)
else:
    raise SystemExit(
        "glslang filesystem compatibility: expected <filesystem> include changed; "
        "refusing a blind patch"
    )

old_location = '''        if(loc.getFilename() == nullptr && shaderFileName != nullptr && absolute) {\n            append(std::filesystem::absolute(shaderFileName).string());\n        } else {\n            std::string location = loc.getStringNameOrNum(false);\n            if (absolute) {\n                append(std::filesystem::absolute(location).string());\n            } else {\n                append(location);\n            }\n        }'''
new_location = '''        if(loc.getFilename() == nullptr && shaderFileName != nullptr && absolute) {\n#if defined(__ANDROID__)\n            // NDK r21e: avoid std::filesystem ABI just for a diagnostic path.\n            append(shaderFileName);\n#else\n            append(std::filesystem::absolute(shaderFileName).string());\n#endif\n        } else {\n            std::string location = loc.getStringNameOrNum(false);\n            if (absolute) {\n#if defined(__ANDROID__)\n                append(location);\n#else\n                append(std::filesystem::absolute(location).string());\n#endif\n            } else {\n                append(location);\n            }\n        }'''

if old_location in info_text:
    info_text = info_text.replace(old_location, new_location, 1)
elif new_location not in info_text:
    raise SystemExit(
        "glslang filesystem compatibility: TInfoSinkBase::location changed; "
        "refusing a blind patch"
    )

info.write_text(info_text, encoding="utf-8")
print("glslang filesystem compatibility: Android diagnostics avoid std::filesystem::absolute")
