# Anchor patch maintenance

The Android builders track the desktop `main` branches, so patches must survive harmless line movement and nearby additions.

## Rules

1. Do not use unified-diff line numbers as anchors. `anchor_patch.py` parses them but does not use them to choose the edit location.
2. Prefer a small, unique block of existing source code around the change.
3. If the same block exists more than once, add `ARENA_ANCHOR: <stable semantic text>` to the hunk header.
4. Python semantic patchers must locate named functions/classes or unique source fragments, verify the expected precondition, and be idempotent.
5. Never increase fuzz to force a changed patch through. Missing or ambiguous context means the patch must be reviewed.
6. Do not re-apply functionality already present in desktop `main`; verify the capability and keep only Android-specific deltas.

## Behaviour

- moved code: patch can still apply when its context remains unique;
- stale hunk coordinates: ignored for placement;
- already-applied change: accepted;
- missing context: hard failure;
- multiple candidate locations: hard failure;
- partial/guessed application: forbidden.

## Quick checks

From the repository root:

```bash
bash -n buildscripts/patches/openmw/apply-*.sh
python3 -m compileall -q -f buildscripts/patches  # optional; remove __pycache__ before packaging
```

For a real compatibility check, point the builder at a fresh desktop source tree and run only the patch stage. Then run it a second time on the already-patched tree to verify idempotency.
