#!/usr/bin/env python3
"""Tiny uiautomator driver for the reference capture: dump, find, tap.

Usage:
  ui.py tap-any LABEL [LABEL...]             tap first visible node matching a label; exit 1 if none
  ui.py center LABEL [LABEL...]              print "x y" of the first match; exit 1 if none
  ui.py wait LABEL [LABEL...] [--timeout S]  wait until a node matching any label is visible
  ui.py labels                               print every labelled node (for logs when a step fails)
  ui.py dump OUT.xml                         save the current hierarchy

A LABEL matches a node's text or content-desc exactly (case-insensitive); `id:NAME` matches
resource-id NAME or `<pkg>:id/NAME`; `~PART` matches text or content-desc containing PART.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout


def hierarchy() -> ET.Element:
    # Dumps fail ("could not get idle state") while something animates; retry a few times.
    for attempt in range(4):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
            return ET.fromstring(adb("shell", "cat", "/sdcard/ui.xml"))
        except (subprocess.CalledProcessError, ET.ParseError):
            if attempt == 3:
                raise
            time.sleep(1)
    raise AssertionError("unreachable")


def matches(node: ET.Element, label: str) -> bool:
    text = (node.get("text") or "").strip().lower()
    desc = (node.get("content-desc") or "").strip().lower()
    if label.startswith("id:"):
        rid = node.get("resource-id") or ""
        return rid == label[3:] or rid.endswith(f":id/{label[3:]}")
    if label.startswith("~"):
        part = label[1:]
        return part in text or part in desc
    return label in (text, desc)


def find(root: ET.Element, labels: list[str]):
    for label in labels:
        wanted = label if label.startswith("id:") else label.lower()
        for node in root.iter("node"):
            if matches(node, wanted):
                return node
    return None


def center(node: ET.Element) -> tuple[int, int]:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def split_timeout(args: list[str]) -> tuple[list[str], float]:
    if "--timeout" in args:
        i = args.index("--timeout")
        return args[:i] + args[i + 2:], float(args[i + 1])
    return args, 30


def main() -> int:
    cmd, *args = sys.argv[1:]
    if cmd == "tap-any":
        node = find(hierarchy(), args)
        if node is None:
            return 1
        x, y = center(node)
        adb("shell", "input", "tap", str(x), str(y))
        print(f"tapped {node.get('text') or node.get('content-desc') or node.get('resource-id')!r} at {x},{y}")
        return 0
    if cmd == "center":
        node = find(hierarchy(), args)
        if node is None:
            return 1
        print(*center(node))
        return 0
    if cmd == "wait":
        labels, timeout = split_timeout(args)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if find(hierarchy(), labels) is not None:
                return 0
            time.sleep(1)
        print(f"timed out waiting for {labels!r}", file=sys.stderr)
        return 1
    if cmd == "labels":
        for node in hierarchy().iter("node"):
            text, desc = node.get("text") or "", node.get("content-desc") or ""
            rid = (node.get("resource-id") or "").rpartition("/")[2]
            if text or desc or rid:
                print(f"  {text!r} desc={desc!r} id={rid} {node.get('bounds')}")
        return 0
    if cmd == "dump":
        hierarchy()
        adb("pull", "/sdcard/ui.xml", args[0])
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
