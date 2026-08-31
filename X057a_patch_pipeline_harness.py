#!/usr/bin/env python3
from pathlib import Path
import re, sys
ROOT=Path(__file__).resolve().parent
errors=[]
for prefix in [Path('buildscripts/patches/openmw'), Path('patches/openmw')]:
    d=ROOT/prefix
    app=(d/'apply-arenamp-patches.sh').read_text(errors='replace')
    for n in ['13-arenamp-x056-server-network.patch','14-arenamp-x056-client-player-menu.patch','15-arenamp-x056-resources.patch']:
        if not (d/n).is_file(): errors.append(f'missing {prefix/n}')
        if f'apply_git_patch "$SCRIPT_DIR/{n}"' not in app: errors.append(f'not applied: {prefix/n}')
    if '13-sync-x056-android.py' in app or 'x056-android-overlay' in app: errors.append(f'overlay reference remains in {prefix}')
    if 'verify_x056_patchset' not in app: errors.append(f'verifier missing in {prefix}')
    counts=[]
    for n in ['13-arenamp-x056-server-network.patch','14-arenamp-x056-client-player-menu.patch','15-arenamp-x056-resources.patch']:
        counts.append((d/n).read_text(errors='replace').count('diff --git '))
    if counts != [57,74,11]: errors.append(f'unexpected split in {prefix}: {counts}')

osc=(ROOT/'app/src/main/java/ui/controls/Osc.kt').read_text(errors='replace')
block=re.search(r'OscLongPressButton\("wait".*?\),\n', osc, re.S)
if not block: errors.append('wait/Y button block missing')
else:
    b=block.group(0)
    if 'holdKey = KeyEvent.KEYCODE_Y' not in b: errors.append('Y is not held physically')
    if 'KEYCODE_F2' in b: errors.append('old long-F2 macro remains')
    if '350L' not in b: errors.append('Y long-press threshold is not 350 ms')

# New feature patches must not touch PC-only launcher/render/shader paths.
for n in ['13-arenamp-x056-server-network.patch','14-arenamp-x056-client-player-menu.patch','15-arenamp-x056-resources.patch']:
    t=(ROOT/'buildscripts/patches/openmw'/n).read_text(errors='replace')
    for forbidden in ['apps/launcher/', 'apps/openmw/mwrender/', 'files/shaders/magnus_cull.comp']:
        if f'diff --git a/{forbidden}' in t: errors.append(f'{n} unexpectedly touches {forbidden}')

if errors:
    print('X057a PATCH PIPELINE HARNESS FAILED')
    for e in errors: print(' -', e)
    sys.exit(1)
print('X057a PATCH PIPELINE HARNESS OK')
print('13/14/15 = 57 + 74 + 11 = 142 build-time patch files; no X056 file overlay.')
