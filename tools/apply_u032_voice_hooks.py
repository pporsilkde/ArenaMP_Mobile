#!/usr/bin/env python3
"""Idempotent, anchor-based hooks for an Android checkout with the U031 sources.
Run from any directory: python3 tools/apply_u032_voice_hooks.py /path/to/Android.
Copy the new U032 voice/control/resource files first. Full hooked files are also supplied.
"""
from pathlib import Path
import sys
root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]

def edit(path, replacements):
    file = root / path
    source = file.read_text(encoding='utf-8')
    for old, new in replacements:
        if new in source:
            continue
        if source.count(old) != 1:
            raise RuntimeError(f'{path}: expected one anchor: {old[:90]!r}; no file was written')
        source = source.replace(old, new)
    return file, source

# Validate every anchor before writing any integration file.
changes = [edit('app/src/main/java/ui/controls/Osc.kt', [
    ('        joystickLeft,\n        joystickRight,',
     '        joystickLeft,\n        joystickRight,\n        OscVoiceButton(), // U032: native push-to-talk and capture indicator'),
    ('    fun placeElements(target: RelativeLayout) {', '''    fun releaseVoiceInput() {
        elements.filterIsInstance<OscVoiceButton>().forEach { it.releaseInput() }
    }

    fun placeElements(target: RelativeLayout) {''')
]), edit('app/src/main/java/ui/activity/GameActivity.kt', [
    ('import ui.controls.Osc', 'import ui.controls.Osc\nimport voice.NativeVoice\nimport voice.VoiceHudView'),
    ('        showControls()\n    }', '''        showControls()
        // U032: passive speech status remains available with external controls.
        layout.addView(VoiceHudView(this), RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            topMargin = (54 * resources.displayMetrics.density).toInt()
        })
    }'''),
    ('    public override fun onDestroy() {', '''    override fun onResume() {
        super.onResume()
        NativeVoice.foreground(hasWindowFocus())
    }

    override fun onPause() {
        osc?.releaseVoiceInput()
        NativeVoice.foreground(false)
        super.onPause()
    }

    public override fun onDestroy() {
        NativeVoice.foreground(false)'''),
    ('        super.onWindowFocusChanged(hasFocus)\n        if (hasFocus)',
     '        NativeVoice.foreground(hasFocus)\n        super.onWindowFocusChanged(hasFocus)\n        if (hasFocus)')
])]
for file, source in changes:
    file.write_text(source, encoding='utf-8')
    print(file)
