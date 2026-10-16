#!/usr/bin/env python3
"""Lists what is statically linked into a Rust native library, grouped by license, for THIRD_PARTY_NOTICES.txt.

    dev/notices/native-crates.py path/to/libmatrix_sdk_ffi.so

Release builds of the Matrix Rust SDK and RTC cores keep the source paths of the crates they were compiled from
(cargo registry and git checkout directories, e.g. ".../index.crates.io-.../tokio-1.40.0/src/..."). This script reads
them from the library itself, looks each crate's license up on crates.io (one request per second) and prints one
"- <SPDX expression>: crates" line per license, plus the bundled C libraries it recognises. Crates that left no path
in the binary (no panics or file!() uses) are not listed: the output is the linked set as recorded by the binary.
Rerun it for every SDK or RTC core update and paste the output into that library's notices entry (DEVELOPMENT.md,
"Third-party notices").
"""
import json
import re
import subprocess
import sys
import time
import urllib.request

REGISTRY = re.compile(rb"registry/src/[^/]+/([A-Za-z0-9_.+-]+)/")
GIT = re.compile(rb"\.cargo/git/checkouts/([A-Za-z0-9_.-]+)/")
VERSIONED = re.compile(r"^(.+)-(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.+-]+)?)$")
GIT_DIR = re.compile(r"^(.+)-[0-9a-f]{16}$")
# Git checkouts are named after the repository; where that differs from the crate name.
GIT_CRATES = {"zxcvbn-rs": "zxcvbn"}
# C libraries compiled into the library by -sys crates, recognised by strings they leave behind.
C_LIBRARIES = {
    b"/aws-lc/crypto/": "ISC AND (Apache-2.0 OR ISC) AND OpenSSL: AWS-LC (C library built by aws-lc-sys; "
    "includes code from BoringSSL and OpenSSL).",
    b"SQLite format 3": "blessing: SQLite (public domain C library built by libsqlite3-sys).",
    b"Java_livekit_org_webrtc_": "BSD-3-Clause: libwebrtc, LiveKit's WebRTC build, and the components it bundles "
    '(see the "WebRTC (libwebrtc, LiveKit build)" entry).',
}


def crates_io(path: str) -> dict:
    request = urllib.request.Request(
        f"https://crates.io/api/v1/crates/{path}",
        headers={"User-Agent": "send-android third-party notices (https://github.com/hanthor/send-android)"},
    )
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except Exception:  # noqa: BLE001 - retried, then reported as unknown
            time.sleep(2 + attempt * 3)
    return {}


def spdx(expression: str) -> str:
    # Old crates write "MIT/Apache-2.0" for "MIT OR Apache-2.0".
    return re.sub(r"\s*/\s*", " OR ", expression.strip())


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    data = subprocess.run(["strings", "-n", "6", sys.argv[1]], check=True, capture_output=True).stdout
    crates: set[tuple[str, str]] = set()
    for match in REGISTRY.finditer(data):
        parsed = VERSIONED.match(match.group(1).decode())
        if parsed:
            crates.add((parsed.group(1), parsed.group(2)))
    for match in GIT.finditer(data):
        parsed = GIT_DIR.match(match.group(1).decode())
        if parsed:
            crates.add((GIT_CRATES.get(parsed.group(1), parsed.group(1)), "(git)"))
    by_license: dict[str, list[str]] = {}
    for name, version in sorted(crates):
        if version == "(git)":
            versions = crates_io(name).get("versions") or [{}]
            license_ = versions[0].get("license")
        else:
            license_ = (crates_io(f"{name}/{version}").get("version") or {}).get("license")
        time.sleep(1)  # crates.io crawler policy: at most one request per second
        by_license.setdefault(spdx(license_) if license_ else "UNKNOWN", []).append(f"{name} {version}")
    for license_, names in sorted(by_license.items(), key=lambda item: (-len(item[1]), item[0])):
        print(f"- {license_}: {', '.join(names)}.")
    for marker, line in C_LIBRARIES.items():
        if marker in data:
            print(f"- {line}")


if __name__ == "__main__":
    main()
