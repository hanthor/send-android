#!/usr/bin/env python3
"""Recover Compose motion specs from R8-obfuscated, jadx-decompiled sources.

Usage: motion_scan.py DECOMPILED_SOURCES_DIR OUT.md

R8 renames androidx classes, so they are re-identified structurally:
  * CubicBezierEasing: its data-class toString literal survives.
  * SpringSpec: constructed as (dampingRatio, stiffness, visibilityThreshold).
  * TweenSpec: constructed as (durationMillis, delayMillis, easing).
Every call site's arguments are tallied; the report lists the most-used specs,
which is what an app's own motion system converges on. Only numbers are
reported -- no decompiled code leaves this script.
"""
import collections
import re
import sys
from pathlib import Path

FLOAT = r"(-?\d+(?:\.\d+)?(?:E-?\d+)?)f"
FOUR_FLOATS = FLOAT + r", " + FLOAT + r", " + FLOAT + r", " + FLOAT
EASING_TOSTRING = re.compile(r'"CubicBezierEasing\(a="')
CLASS_DECL = re.compile(r"\b(?:final )?class (\w+)")
# new X(0.8f, 380.0f, ...)  -- candidate springs
SPRING_CALL = re.compile(r"new (\w+)\(" + FLOAT + r", " + FLOAT + r"(?:, [^()]*?)?\)")
# new X(300, 0, easing)    -- candidate tweens
TWEEN_CALL = re.compile(r"new (\w+)\((\d{2,4}), (\d{1,4}), ([\w.$]+)\)")


def main() -> int:
    root, out = Path(sys.argv[1]), Path(sys.argv[2])
    easing_cls = None
    spring_votes, tween_votes = collections.Counter(), collections.Counter()
    spring_args = collections.defaultdict(collections.Counter)
    tween_args = collections.defaultdict(collections.Counter)
    texts = []
    for f in root.rglob("*.java"):
        try:
            text = f.read_text(errors="ignore")
        except OSError:
            continue
        if easing_cls is None and EASING_TOSTRING.search(text):
            m = CLASS_DECL.search(text)
            easing_cls = m.group(1) if m else None
        for m in SPRING_CALL.finditer(text):
            damping, stiffness = float(m.group(2)), float(m.group(3))
            if 0.05 <= damping <= 1.0 and 10 <= stiffness <= 20000:
                spring_votes[m.group(1)] += 1
                spring_args[m.group(1)][(damping, stiffness)] += 1
        for m in TWEEN_CALL.finditer(text):
            duration, delay = int(m.group(2)), int(m.group(3))
            if 16 <= duration <= 3000 and delay <= 3000:
                tween_votes[m.group(1)] += 1
                tween_args[m.group(1)][(duration, delay, m.group(4))] += 1
        texts.append(text)

    easing_counts = collections.Counter()
    field_curves = {}
    if easing_cls:
        call = re.compile(r"new " + re.escape(easing_cls) + r"\(" + FOUR_FLOATS + r"\)")
        assign = re.compile(r"\b(\w+) = new " + re.escape(easing_cls) + r"\(" + FOUR_FLOATS + r"\)")
        for text in texts:
            for m in call.finditer(text):
                easing_counts[tuple(float(g) for g in m.groups())] += 1
            owner = CLASS_DECL.search(text)
            if owner is not None:
                for m in assign.finditer(text):
                    field_curves[f"{owner.group(1)}.{m.group(1)}"] = tuple(float(g) for g in m.groups()[1:])

    lines = ["# Google Messages motion specs (recovered from bytecode)", ""]
    lines += [f"## Easing curves (`CubicBezierEasing` = `{easing_cls}`)", "", "| curve | definitions |", "|---|---|"]
    lines += [f"| cubic-bezier{curve} | {n} |" for curve, n in easing_counts.most_common(40)]

    spring_cls = spring_votes.most_common(1)[0][0] if spring_votes else None
    lines += ["", f"## Springs (`SpringSpec` ≈ `{spring_cls}`)", "", "| dampingRatio | stiffness | uses |", "|---|---|---|"]
    if spring_cls:
        lines += [f"| {d} | {s} | {n} |" for (d, s), n in spring_args[spring_cls].most_common(40)]

    tween_cls = tween_votes.most_common(1)[0][0] if tween_votes else None
    lines += ["", f"## Tweens (`TweenSpec` ≈ `{tween_cls}`)", "", "| duration ms | delay ms | easing | uses |", "|---|---|---|---|"]
    if tween_cls:
        for (d, dl, e), n in tween_args[tween_cls].most_common(60):
            curve = field_curves.get(e)
            lines.append(f"| {d} | {dl} | {'cubic-bezier' + str(curve) if curve else '`' + e + '`'} | {n} |")

    lines += ["", "## Class-identification votes", "",
              f"- spring candidates: {spring_votes.most_common(5)}",
              f"- tween candidates: {tween_votes.most_common(5)}"]
    out.write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
