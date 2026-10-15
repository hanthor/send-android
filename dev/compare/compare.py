#!/usr/bin/env python3
"""Pair Google Messages reference captures with Send captures and report differences.

Usage: compare.py REF_DIR SEND_DIR OUT_DIR DENSITY_DPI

Writes OUT_DIR/<screen>.png side-by-side composites and OUT_DIR/report.md with
dp-measured element bounds (from uiautomator dumps) and dominant palettes.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

# screen -> (Messages capture stem, Send full-device capture stem)
PAIRS = {
    "home": ("gm-home", "send-home-screen-device"),
    "conversation": ("gm-conversation", "send-conversation-screen-device"),
    "new-chat": ("gm-new-chat", "send-new-chat-dialog-device"),
    # Newer features (visual audit).
    "new-group": ("gm-new-group", "send-new-group-device"),
    "attachments": ("gm-attachments", "send-attachments-device"),
    "gallery": ("gm-gallery", "send-gallery-device"),
    "voice": ("gm-voice-recording", "send-voice-hint-device"),
    "voice-locked": ("gm-voice-locked", "send-voice-locked-device"),
    "conversation-menu": ("gm-conversation-menu", "send-conversation-menu-device"),
    "conversation-search": ("gm-conversation-search", "send-conversation-search-device"),
    "message-actions": ("gm-message-actions", "send-message-actions-device"),
    "reply-preview": ("gm-reply-preview", "send-reply-preview-device"),
    "emoji-picker": ("gm-emoji-picker", "send-emoji-picker-device"),
    "media-viewer": ("gm-media-viewer", "send-media-viewer-device"),
    "typing": ("gm-typing", "send-typing-device"),
    "account": ("gm-account", "send-account-device"),
    "settings": ("gm-settings", "send-security-device"),
    "notification": ("gm-notification", "send-notification-device"),
}

# element -> labels matched against text / content-desc (first hit wins), or an EditText class match.
ELEMENTS = {
    "search bar": ["search conversations", "search", "search messages"],
    "start-chat FAB": ["start chat", "new chat"],
    "message field": ["@edittext"],
    "send / mic button": ["send", "send sms", "send message", "voice message", "record voice message",
                          "show record a voice message screen"],
    "attach button": ["attach", "show attach content screen"],
    "gallery button": ["gallery", "show attach media screen"],
    "emoji button": ["emoji", "show attach emoji and stickers screen"],
    "back button": ["back", "navigate up"],
    "call button": ["call", "voice call"],
    "overflow menu": ["more options", "more"],
    # Message selection actions (Send's content descriptions first, then Messages' wording).
    "reply action": ["reply"],
    "copy action": ["copy", "copy text"],
    "edit action": ["edit"],
    "delete action": ["delete"],
    "forward action": ["forward", "share"],
    "info action": ["info", "details", "view details"],
    "close selection": ["close", "exit selection", "done"],
}


def nodes(xml_path: Path):
    if not xml_path.exists():
        return []
    root = ET.parse(xml_path).getroot()
    found = []
    for node in root.iter("node"):
        nums = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
        if len(nums) != 4:
            continue
        found.append({
            "text": (node.get("text") or "").strip().lower(),
            "desc": (node.get("content-desc") or "").strip().lower(),
            "cls": node.get("class") or "",
            "bounds": nums,
        })
    return found


def locate(all_nodes, labels):
    for label in labels:
        for n in all_nodes:
            if label == "@edittext" and n["cls"].endswith("EditText"):
                return n
            if label in (n["text"], n["desc"]):
                return n
    return None


def dp(px: int, dpi: int) -> str:
    return f"{px * 160 / dpi:.0f}"


def palette(png: Path, count: int = 5) -> str:
    img = Image.open(png).convert("RGB").resize((180, 400))
    quant = img.quantize(colors=count)
    pal = quant.getpalette()
    shares = sorted(quant.getcolors(), reverse=True)
    total = sum(c for c, _ in shares)
    return " ".join(
        f"`#{pal[i*3]:02X}{pal[i*3+1]:02X}{pal[i*3+2]:02X}` {c*100/total:.0f}%" for c, i in shares
    )


def composite(ref: Path, ours: Path, out: Path) -> None:
    a, b = Image.open(ref).convert("RGB"), Image.open(ours).convert("RGB")
    h = 1600
    a = a.resize((a.width * h // a.height, h))
    b = b.resize((b.width * h // b.height, h))
    gap, label_h = 24, 56
    canvas = Image.new("RGB", (a.width + b.width + gap, h + label_h), "white")
    canvas.paste(a, (0, label_h))
    canvas.paste(b, (a.width + gap, label_h))
    draw = ImageDraw.Draw(canvas)
    draw.text((16, 16), "Google Messages", fill="black")
    draw.text((a.width + gap + 16, 16), "Send", fill="black")
    canvas.save(out)

def motion_span(clip: Path) -> str:
    """Duration of the main animated span: first to last frame differing from its predecessor."""
    import subprocess

    probe = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", str(clip), "-vf", "select='gt(scene,0.002)',showinfo", "-f", "null", "-"],
        capture_output=True, text=True,
    )
    times = [float(t) for t in re.findall(r"pts_time:([0-9.]+)", probe.stderr)]
    if len(times) < 2:
        return "no motion detected"
    # Cluster changed frames; the longest cluster (gaps < 120 ms) is the transition.
    clusters, current = [], [times[0]]
    for t in times[1:]:
        if t - current[-1] < 0.12:
            current.append(t)
        else:
            clusters.append(current)
            current = [t]
    clusters.append(current)
    main = max(clusters, key=lambda c: c[-1] - c[0])
    return f"{(main[-1] - main[0]) * 1000:.0f} ms of motion across {len(main)} changed frames (starts at {main[0]:.2f}s)"



def main() -> int:
    ref_dir, send_dir, out_dir = map(Path, sys.argv[1:4])
    dpi = int(sys.argv[4])
    out_dir.mkdir(parents=True, exist_ok=True)
    lines = ["# Google Messages vs Send", "", f"Same emulator, {dpi} dpi. Sizes in dp (`w×h @ x,y`).", ""]
    version = ref_dir / "gm-version.txt"
    if version.exists():
        lines += [f"Messages build: `{version.read_text().strip()}`", ""]
    for screen, (ref_stem, our_stem) in PAIRS.items():
        ref_png, our_png = ref_dir / f"{ref_stem}.png", send_dir / f"{our_stem}.png"
        lines += [f"## {screen}", ""]
        if not (ref_png.exists() and our_png.exists()):
            lines += [f"_Missing capture: {ref_png.name if not ref_png.exists() else our_png.name}_", ""]
            skipped = ref_dir / f"{ref_stem}.skipped.txt"
            if not ref_png.exists() and skipped.exists():
                lines += [f"_Messages: {skipped.read_text().strip()}_", ""]
            continue
        composite(ref_png, our_png, out_dir / f"{screen}.png")
        lines += [f"![{screen}]({screen}.png)", ""]
        ref_nodes, our_nodes = nodes(ref_dir / f"{ref_stem}.xml"), nodes(send_dir / f"{our_stem}.xml")
        lines += ["| element | Messages | Send | Δh |", "|---|---|---|---|"]
        for name, labels in ELEMENTS.items():
            r, o = locate(ref_nodes, labels), locate(our_nodes, labels)
            if r is None and o is None:
                continue

            def fmt(n):
                if n is None:
                    return "—"
                x1, y1, x2, y2 = n["bounds"]
                return f"{dp(x2-x1, dpi)}×{dp(y2-y1, dpi)} @ {dp(x1, dpi)},{dp(y1, dpi)}"

            delta = "—"
            if r is not None and o is not None:
                delta = f"{(o['bounds'][3]-o['bounds'][1] - (r['bounds'][3]-r['bounds'][1])) * 160 / dpi:+.0f}"
            lines.append(f"| {name} | {fmt(r)} | {fmt(o)} | {delta} |")
        lines += ["", f"Palette — Messages: {palette(ref_png)}", "", f"Palette — Send: {palette(our_png)}", ""]
    lines += ["## Motion", ""]
    for clip in sorted(ref_dir.glob("gm-*.mp4")):
        lines.append(f"- `{clip.name}`: {motion_span(clip)}")
    lines.append("")
    (out_dir / "report.md").write_text("\n".join(lines))
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
