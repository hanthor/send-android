#!/usr/bin/env python3
"""Tiny uiautomator driver for the reference capture: dump, find, tap.

Usage:
  ui.py tap-any LABEL [LABEL...]   tap first visible node whose text/desc matches; exit 1 if none
  ui.py wait LABEL [--timeout S]   wait until a node with text/desc LABEL is visible
  ui.py dump OUT.xml               save the current hierarchy
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout


def hierarchy() -> ET.Element:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/ui.xml"))


def find(root: ET.Element, labels: list[str]):
    wanted = [label.lower() for label in labels]
    for label in wanted:
        for node in root.iter("node"):
            text = (node.get("text") or "").strip().lower()
            desc = (node.get("content-desc") or "").strip().lower()
            if label in (text, desc):
                return node
    return None


def center(node: ET.Element) -> tuple[int, int]:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def main() -> int:
    cmd, *args = sys.argv[1:]
    if cmd == "tap-any":
        node = find(hierarchy(), args)
        if node is None:
            return 1
        x, y = center(node)
        adb("shell", "input", "tap", str(x), str(y))
        print(f"tapped {node.get('text') or node.get('content-desc')!r} at {x},{y}")
        return 0
    if cmd == "center":
        node = find(hierarchy(), args)
        if node is None:
            return 1
        print(*center(node))
        return 0
    if cmd == "wait":
        label = args[0]
        timeout = float(args[2]) if len(args) > 2 and args[1] == "--timeout" else 30
        deadline = time.time() + timeout
        while time.time() < deadline:
            if find(hierarchy(), [label]) is not None:
                return 0
            time.sleep(1)
        print(f"timed out waiting for {label!r}", file=sys.stderr)
        return 1
    if cmd == "dump":
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        adb("pull", "/sdcard/ui.xml", args[0])
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
