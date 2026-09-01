#!/usr/bin/env python3
"""Ensure ArenaMP's Russo HUD/chat font is copied into build resources.

AMP registers the MyGUI font in openmw_font.xml and keeps RussoOne-Regular.ttf
in files/mygui, but older CMakeLists.txt revisions forgot to list the TTF in
MYGUI_FILES. Desktop source-tree lookups can mask that omission; Android packs
only the generated build/resources tree, so the HUD/chat text becomes blank.
"""
from pathlib import Path
import sys


def fail(msg: str) -> None:
    raise SystemExit(f"ERROR: {msg}")


def main() -> int:
    if len(sys.argv) != 2:
        fail("usage: 14-ensure-russo-font-resource.py <ArenaMP source>")

    root = Path(sys.argv[1])
    cmake = root / "files/mygui/CMakeLists.txt"
    font_xml = root / "files/mygui/openmw_font.xml"
    font_ttf = root / "files/mygui/RussoOne-Regular.ttf"

    if not cmake.is_file():
        fail(f"missing {cmake}")
    if not font_xml.is_file():
        fail(f"missing {font_xml}")
    if not font_ttf.is_file():
        fail("AMP/main is missing files/mygui/RussoOne-Regular.ttf")

    xml = font_xml.read_text(encoding="utf-8")
    if 'name="Russo"' not in xml or 'value="RussoOne-Regular.ttf"' not in xml:
        fail("openmw_font.xml does not register Russo -> RussoOne-Regular.ttf")

    text = cmake.read_text(encoding="utf-8")
    if "RussoOne-Regular.ttf" in text:
        print("==> Russo font already listed in MYGUI_FILES")
        return 0

    start = text.find("set(MYGUI_FILES")
    if start < 0:
        fail("could not find set(MYGUI_FILES ...) anchor")
    end = text.find("\n)", start)
    if end < 0:
        fail("could not find end of MYGUI_FILES list")

    block = text[start:end]
    # Use a semantic sibling anchor inside the same resource list. Pelagiad.ttf is
    # an existing shipped font and is stable across the ArenaMP/OpenMW lineage.
    anchors = ["    Pelagiad.ttf", "\tPelagiad.ttf", "Pelagiad.ttf"]
    chosen = next((a for a in anchors if a in block), None)
    if chosen is None:
        fail("could not find Pelagiad.ttf anchor inside MYGUI_FILES")

    pos = start + block.find(chosen)
    line_end = text.find("\n", pos)
    if line_end < 0 or line_end > end:
        fail("invalid Pelagiad.ttf anchor position")

    indent = text[pos:line_end][: len(text[pos:line_end]) - len(text[pos:line_end].lstrip())]
    insertion = f"\n{indent}RussoOne-Regular.ttf"
    text = text[:line_end] + insertion + text[line_end:]
    cmake.write_text(text, encoding="utf-8")

    verify = cmake.read_text(encoding="utf-8")
    vstart = verify.find("set(MYGUI_FILES")
    vend = verify.find("\n)", vstart)
    if "RussoOne-Regular.ttf" not in verify[vstart:vend]:
        fail("Russo font insertion verification failed")

    print("==> added RussoOne-Regular.ttf to MYGUI_FILES")
    return 0


if __name__ == "__main__":
    sys.exit(main())
