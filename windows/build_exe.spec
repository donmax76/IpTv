# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec file for TVViewer Windows

a = Analysis(
    ['tvviewer.py'],
    pathex=[],
    binaries=[],
    datas=[],
    hiddenimports=['m3u_parser', 'epg_parser'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='TVViewer',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,
    icon=None,
)
