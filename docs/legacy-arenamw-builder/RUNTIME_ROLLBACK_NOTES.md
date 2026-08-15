# ArenaMW Android runtime rollback

This build keeps the AMW repository/incremental CI work, but rolls back the two runtime graphics changes introduced together in `RUNTIME_SHADER_RQCB_FIX`:

- `LIBGL_SIMPLE_SHADERCONV=0` is reverted to the last known-working `1` for the current OpenMW 0.47/ArenaMW shader set.
- `06-android-rqcb-postdraw-capture-camera.patch` is quarantined and is no longer applied.
- Android/NG-GL4ES logging stays enabled in release CI builds (`LIBGL_LOG=1`, `OPENMW_DISABLE_LOGS=0`) until the renderer is stable.

The old `osgOQ: QG: Invalid RQCB` warning may return. That is intentional for this bisect: first restore a launching/rendering game, then fix RQCB independently without changing the shader converter in the same build.
