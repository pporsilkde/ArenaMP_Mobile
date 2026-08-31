#!/usr/bin/env python3
"""Arena Android anchor-based unified diff applicator.

Unlike traditional patch application, hunk line numbers are never used to locate
changes. Each textual change is resolved by unique source text plus surrounding
context anchors. If a change is ambiguous, the tool fails closed instead of
modifying an uncertain location.

Supports normal text modifications/additions. Git binary patch sections are
applied per-file through `git apply --include=...` because they contain no line
anchors to drift.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable


@dataclass
class Hunk:
    header: str
    lines: list[str] = field(default_factory=list)


@dataclass
class FilePatch:
    old_path: str | None = None
    new_path: str | None = None
    hunks: list[Hunk] = field(default_factory=list)
    is_new: bool = False
    is_deleted: bool = False
    is_binary: bool = False

    @property
    def target(self) -> str:
        p = self.new_path if self.new_path not in (None, '/dev/null') else self.old_path
        if p is None:
            raise ValueError('patch entry has no path')
        return strip_prefix(p)


def strip_prefix(p: str) -> str:
    p = p.strip().split('\t', 1)[0]
    if p.startswith('a/') or p.startswith('b/'):
        return p[2:]
    return p


def parse_patch(path: Path) -> list[FilePatch]:
    raw = path.read_text(encoding='utf-8', errors='surrogateescape').splitlines(keepends=True)
    files: list[FilePatch] = []
    cur: FilePatch | None = None
    hunk: Hunk | None = None
    remaining_old: int | None = None
    remaining_new: int | None = None
    i = 0
    while i < len(raw):
        line = raw[i]
        if line.startswith('diff --git '):
            m = re.match(r'diff --git a/(.*?) b/(.*?)\r?\n?$', line)
            if not m:
                raise ValueError(f'unsupported diff header: {line.rstrip()}')
            cur = FilePatch(old_path='a/' + m.group(1), new_path='b/' + m.group(2))
            files.append(cur)
            hunk = None
        elif line.startswith('--- '):
            old = line[4:].strip().split('\t', 1)[0]
            if cur is None or cur.hunks:
                cur = FilePatch()
                files.append(cur)
            cur.old_path = old
            hunk = None
        elif line.startswith('+++ '):
            if cur is None:
                raise ValueError('+++ without file entry')
            cur.new_path = line[4:].strip().split('\t', 1)[0]
        elif line.startswith('new file mode '):
            if cur is None:
                raise ValueError('new file mode without file entry')
            cur.is_new = True
        elif line.startswith('deleted file mode '):
            if cur is None:
                raise ValueError('deleted file mode without file entry')
            cur.is_deleted = True
        elif line.startswith('GIT binary patch') or line.startswith('Binary files '):
            if cur is None:
                raise ValueError('binary marker without file entry')
            cur.is_binary = True
            hunk = None
        elif line.startswith('@@ '):
            if cur is None:
                raise ValueError('hunk without file entry')
            hm = re.match(r'@@\s+-\d+(?:,(\d+))?\s+\+\d+(?:,(\d+))?\s+@@', line)
            if not hm:
                raise ValueError(f'unsupported hunk header: {line.rstrip()}')
            remaining_old = int(hm.group(1) or '1')
            remaining_new = int(hm.group(2) or '1')
            hunk = Hunk(line.rstrip('\r\n'))
            cur.hunks.append(hunk)
        elif hunk is not None:
            if line.startswith('\\ No newline at end of file'):
                pass
            elif line.startswith((' ', '+', '-')):
                hunk.lines.append(line)
                if line.startswith(' '):
                    remaining_old -= 1
                    remaining_new -= 1
                elif line.startswith('-'):
                    remaining_old -= 1
                else:
                    remaining_new -= 1
                if remaining_old == 0 and remaining_new == 0:
                    hunk = None
            else:
                raise ValueError(f'malformed hunk body in {path.name}: {line.rstrip()}')
        i += 1
    return [f for f in files if (f.old_path or f.new_path) and (f.hunks or f.is_binary)]


def norm_line(s: str) -> str:
    # Ignore indentation and trailing whitespace for fallback matching, but keep
    # the actual replacement bytes from the patch.
    return ' '.join(s.strip().split())


def seq_matches(lines: list[str], seq: list[str], normalized: bool = False) -> list[int]:
    if not seq:
        return []
    if normalized:
        hay = [norm_line(x) for x in lines]
        needle = [norm_line(x) for x in seq]
    else:
        hay, needle = lines, seq
    n = len(needle)
    if n > len(hay):
        return []
    return [i for i in range(0, len(hay) - n + 1) if hay[i:i+n] == needle]


def context_before(hunk_lines: list[str], start: int, limit: int = 4) -> list[str]:
    out: list[str] = []
    i = start - 1
    while i >= 0 and len(out) < limit:
        if hunk_lines[i].startswith(' '):
            out.append(hunk_lines[i][1:])
            i -= 1
        else:
            break
    out.reverse()
    return out


def context_after(hunk_lines: list[str], end: int, limit: int = 4) -> list[str]:
    out: list[str] = []
    i = end
    while i < len(hunk_lines) and len(out) < limit:
        if hunk_lines[i].startswith(' '):
            out.append(hunk_lines[i][1:])
            i += 1
        else:
            break
    return out


def anchor_score(lines: list[str], pos: int, old_len: int, before: list[str], after: list[str]) -> int:
    score = 0
    for ctx, at in ((before, pos - len(before)), (after, pos + old_len)):
        if not ctx:
            continue
        if at >= 0 and at + len(ctx) <= len(lines):
            if lines[at:at+len(ctx)] == ctx:
                score += 100 + len(ctx)
            elif [norm_line(x) for x in lines[at:at+len(ctx)]] == [norm_line(x) for x in ctx]:
                score += 50 + len(ctx)
    return score


def choose_candidate(lines: list[str], candidates: list[int], old_len: int,
                     before: list[str], after: list[str], label: str, semantic_anchor: int | None = None) -> int:
    if len(candidates) == 1:
        return candidates[0]
    if semantic_anchor is not None and candidates:
        # Named function/class anchors are authoritative. Prefer the first match
        # after the anchor; if all are before it, choose the uniquely nearest one.
        ranked_sem = sorted(((0 if p >= semantic_anchor else 1, abs(p - semantic_anchor), p) for p in candidates))
        if len(ranked_sem) == 1 or ranked_sem[0][:2] < ranked_sem[1][:2]:
            return ranked_sem[0][2]
    ranked = sorted(((anchor_score(lines, p, old_len, before, after), p) for p in candidates), reverse=True)
    if ranked and ranked[0][0] > 0 and (len(ranked) == 1 or ranked[0][0] > ranked[1][0]):
        return ranked[0][1]
    raise RuntimeError(f'{label}: anchor is ambiguous ({len(candidates)} candidate locations)')


def semantic_anchor_position(lines: list[str], hunk: Hunk) -> int | None:
    m = re.search(r'ARENA_ANCHOR:\s*(.+)$', hunk.header)
    if not m:
        return None
    needle = norm_line(m.group(1))
    hits = [i for i, line in enumerate(lines) if needle and needle in norm_line(line)]
    if len(hits) != 1:
        raise RuntimeError(f'{hunk.header}: semantic anchor {m.group(1)!r} matched {len(hits)} lines')
    return hits[0]


def locate_change(lines: list[str], old: list[str], before: list[str], after: list[str], label: str, semantic_anchor: int | None = None) -> tuple[int, int]:
    exact = seq_matches(lines, old, normalized=False)
    if exact:
        p = choose_candidate(lines, exact, len(old), before, after, label, semantic_anchor)
        return p, p + len(old)
    relaxed = seq_matches(lines, old, normalized=True)
    if relaxed:
        p = choose_candidate(lines, relaxed, len(old), before, after, label + ' (whitespace-normalized)', semantic_anchor)
        return p, p + len(old)
    raise RuntimeError(f'{label}: source block not found by content anchors')


def insertion_candidates(lines: list[str], before: list[str], after: list[str]) -> list[int]:
    candidates: set[int] = set()
    # Prefer pairs of surrounding anchors. This tolerates arbitrary line movement.
    for normalized in (False, True):
        bpos = seq_matches(lines, before, normalized) if before else []
        apos = seq_matches(lines, after, normalized) if after else []
        if before and after:
            for b in bpos:
                insert = b + len(before)
                for a in apos:
                    if a == insert:
                        candidates.add(insert)
        elif before:
            for b in bpos:
                candidates.add(b + len(before))
        elif after:
            for a in apos:
                candidates.add(a)
        if candidates:
            break
    return sorted(candidates)


def already_applied(lines: list[str], new: list[str], before: list[str], after: list[str],
                    semantic_anchor: int | None = None) -> bool:
    if not new:
        return False
    candidates = seq_matches(lines, new, False) or seq_matches(lines, new, True)
    if len(candidates) == 1:
        return True
    if len(candidates) > 1:
        # Repeated dependency blocks are common (MyGUI has two intentionally
        # identical resource-config branches).  A semantic anchor must remain
        # authoritative for idempotence as well as for first application.
        if semantic_anchor is not None:
            try:
                choose_candidate(lines, candidates, len(new), before, after,
                                 'already-applied semantic anchor', semantic_anchor)
                return True
            except RuntimeError:
                pass
        scored = [anchor_score(lines, p, len(new), before, after) for p in candidates]
        return max(scored, default=0) > 0 and scored.count(max(scored)) == 1
    return False


def apply_hunk(lines: list[str], hunk: Hunk, file_label: str) -> tuple[list[str], int, int]:
    # First try the complete hunk preimage. This is the strongest and safest
    # anchor: line coordinates are ignored, but all unchanged source context is
    # retained. Most upstream movement is handled here with no fuzz at all.
    old_full = [x[1:] for x in hunk.lines if x.startswith((' ', '-'))]
    new_full = [x[1:] for x in hunk.lines if x.startswith((' ', '+'))]
    label = f'{file_label} {hunk.header}'
    semantic_anchor = semantic_anchor_position(lines, hunk)

    if old_full:
        exact = seq_matches(lines, old_full, normalized=False)
        if exact:
            p = choose_candidate(lines, exact, len(old_full), [], [], label, semantic_anchor)
            lines[p:p+len(old_full)] = new_full
            return lines, 1, 0
        if not exact:
            relaxed = seq_matches(lines, old_full, normalized=True)
            if relaxed:
                p = choose_candidate(lines, relaxed, len(old_full), [], [], label, semantic_anchor)
                lines[p:p+len(old_full)] = new_full
                return lines, 1, 0
        # Idempotence before using the narrower fallback.
        already = seq_matches(lines, new_full, normalized=False) or seq_matches(lines, new_full, normalized=True)
        if len(already) == 1:
            return lines, 0, 1
        if len(already) > 1 and semantic_anchor is not None:
            # If the replacement exists in several repeated blocks, resolve the
            # intended one using the same semantic anchor as normal application.
            choose_candidate(lines, already, len(new_full), [], [], label, semantic_anchor)
            return lines, 0, 1

    # Upstream edited unrelated context *inside* the hunk. Fall back to each
    # contiguous change group and use the nearest unchanged lines as anchors.
    # This still never consults @@ old/new line numbers.
    i = 0
    applied = 0
    present = 0
    while i < len(hunk.lines):
        if hunk.lines[i].startswith(' '):
            i += 1
            continue
        if not hunk.lines[i].startswith(('+', '-')):
            i += 1
            continue
        start = i
        group: list[str] = []
        while i < len(hunk.lines) and hunk.lines[i].startswith(('+', '-')):
            group.append(hunk.lines[i])
            i += 1
        end = i
        old = [x[1:] for x in group if x.startswith('-')]
        new = [x[1:] for x in group if x.startswith('+')]
        before = context_before(hunk.lines, start)
        after = context_after(hunk.lines, end)

        if old:
            try:
                a, b = locate_change(lines, old, before, after, label, semantic_anchor)
            except RuntimeError:
                if already_applied(lines, new, before, after, semantic_anchor):
                    present += 1
                    continue
                raise
            lines[a:b] = new
            applied += 1
        else:
            if already_applied(lines, new, before, after, semantic_anchor):
                present += 1
                continue
            candidates = insertion_candidates(lines, before, after)
            if len(candidates) == 1:
                p = candidates[0]
            elif semantic_anchor is not None and candidates:
                p = choose_candidate(lines, candidates, 0, before, after, label, semantic_anchor)
            else:
                raise RuntimeError(f'{label}: insertion anchors resolved to {len(candidates)} locations')
            lines[p:p] = new
            applied += 1
    return lines, applied, present


def git_apply_binary(root: Path, patch: Path, rel: str, check: bool) -> str:
    base = ['git', '-C', str(root), 'apply', '--whitespace=nowarn', f'--include={rel}']
    if check:
        base.append('--check')
    r = subprocess.run(base + [str(patch)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if r.returncode == 0:
        return 'applicable'
    rev = ['git', '-C', str(root), 'apply', '--reverse', '--check', '--whitespace=nowarn', f'--include={rel}', str(patch)]
    rr = subprocess.run(rev, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if rr.returncode == 0:
        return 'present'
    raise RuntimeError(f'{rel}: binary patch is neither applicable nor present\n{r.stderr.strip()}')


def apply_file(root: Path, patch_path: Path, fp: FilePatch, check: bool) -> tuple[int, int]:
    rel = fp.target
    target = root / rel
    if fp.is_binary:
        state = git_apply_binary(root, patch_path, rel, check)
        return ((1, 0) if state == 'applicable' else (0, 1))

    if fp.is_deleted:
        raise RuntimeError(f'{rel}: deleted-file patches are intentionally unsupported by anchor engine')

    if not target.exists():
        if not fp.is_new and fp.old_path != '/dev/null':
            raise RuntimeError(f'{rel}: target file does not exist')
        # New text files have no pre-existing anchor by definition. Their path is
        # the stable anchor, so materialize the complete +/context payload.
        created: list[str] = []
        for h in fp.hunks:
            created.extend(x[1:] for x in h.lines if x.startswith(('+', ' ')))
        if not check:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(''.join(created), encoding='utf-8', errors='surrogateescape')
        return 1, 0

    content = target.read_text(encoding='utf-8', errors='surrogateescape').splitlines(keepends=True)

    # Idempotent new-file case: accept only exact content, never overwrite a
    # different upstream file that later appeared at the same path.
    if fp.is_new or fp.old_path == '/dev/null':
        expected: list[str] = []
        for h in fp.hunks:
            expected.extend(x[1:] for x in h.lines if x.startswith(('+', ' ')))
        if content == expected:
            return 0, 1
        raise RuntimeError(f'{rel}: new-file path already exists with different content')

    applied = present = 0
    for h in fp.hunks:
        content, a, p = apply_hunk(content, h, rel)
        applied += a
        present += p

    if not check and applied:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(''.join(content), encoding='utf-8', errors='surrogateescape')
    return applied, present


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('source_dir')
    ap.add_argument('patch_file')
    ap.add_argument('--check', action='store_true')
    ap.add_argument('--exclude', action='append', default=[])
    args = ap.parse_args()

    root = Path(args.source_dir).resolve()
    patch = Path(args.patch_file).resolve()
    fps = parse_patch(patch)
    if not fps:
        raise SystemExit(f'no supported diff entries found in {patch.name}')

    total_applied = total_present = 0
    for fp in fps:
        rel = fp.target
        if rel in args.exclude:
            continue
        a, p = apply_file(root, patch, fp, args.check)
        total_applied += a
        total_present += p

    mode = 'check' if args.check else 'apply'
    print(f'==> anchor-{mode} {patch.name}: changes={total_applied}, already-present={total_present}')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError) as e:
        print(f'ERROR: {e}', file=sys.stderr)
        raise SystemExit(27)
