#!/usr/bin/env python3
"""Verify the final packaged APK, after Android asset filtering/compression."""
from pathlib import Path
import sys
import zipfile

REQUIRED = (
    'assets/arenamp-server/server/scripts/serverCore.lua',
    'assets/arenamp-server/server/scripts/config.lua',
    'assets/arenamp-server/tes3mp-server-default.cfg',
    'assets/arenamp-server/resources/version',
    'assets/libopenmw/resources/version',
    'assets/libopenmw/openmw/defaults.bin',
)

def verify(path):
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        missing = [name for name in REQUIRED if name not in names]
        client_abis = {n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('/libtes3mp.so')}
        if not client_abis:
            missing.append('lib/<abi>/libtes3mp.so')
        for abi in sorted(client_abis):
            for library in ('libarenamp_server.so', 'libc++_shared.so'):
                name = f'lib/{abi}/{library}'
                if name not in names:
                    missing.append(name)
        if missing:
            raise ValueError(f'{path}: incomplete APK payload:\n' + '\n'.join(missing))
        for name in REQUIRED:
            if not apk.read(name):
                raise ValueError(f'{path}: empty required APK asset: {name}')
        print(f'{path.name}: server payload OK; ABIs={", ".join(sorted(client_abis))}')

if __name__ == '__main__':
    source = Path(sys.argv[1])
    apks = sorted(source.rglob('*.apk')) if source.is_dir() else [source]
    if not apks:
        raise SystemExit('No APK files to verify')
    for path in apks:
        verify(path)
