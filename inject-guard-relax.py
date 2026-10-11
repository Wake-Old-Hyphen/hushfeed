#!/usr/bin/env python3
"""
Neutralizes the Device privacy guard's hard failure when no NetworkCapabilities.hasTransport 
call site exists in the target APK (e.g., TikTok 47.1.4).

Features:
- Preserves clipboard interception while skipping the local-network block if no targets exist.
- Self-healing: Becomes a no-op with a warning if the upstream author changes or removes this check.
"""
import logging
import sys
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger(__name__)

FILE_PATH = Path("patches/src/main/kotlin/app/morphe/patches/tiktok/privacy/DevicePrivacyGuardPatch.kt")
TARGET_MSG = "Device privacy guard: no NetworkCapabilities.hasTransport call site was found."
WARNING_LOG = (
    'println("[Device privacy guard] WARNING: no NetworkCapabilities.hasTransport '
    'call site on this build; local-network block skipped, clipboard guard still active.")'
)


def main() -> int:
    if not FILE_PATH.exists():
        logger.error(f"Target file not found: {FILE_PATH}")
        return 1

    try:
        lines = FILE_PATH.read_text(encoding="utf-8").splitlines(keepends=True)
    except Exception as e:
        logger.error(f"Failed to read {FILE_PATH}: {e}")
        return 1

    modified_count = 0

    for idx, line in enumerate(lines):
        if TARGET_MSG in line:
            indent = line[:len(line) - len(line.lstrip())]
            nl = "\n" if line.endswith("\n") else ""
            lines[idx] = f"{indent}{WARNING_LOG}{nl}"
            modified_count += 1

    if modified_count == 0:
        logger.warning("hasTransport throw line not found. The author may have changed it.")
        logger.info("Context lines mentioning hasTransport:")
        for idx, line in enumerate(lines):
            if "hasTransport" in line:
                logger.info(f"  Line {idx + 1}: {line.rstrip()}")
        logger.info("Continuing without relaxation; the patch step will fail loudly if still incompatible.")
    else:
        logger.info(f"Relaxed {modified_count} hasTransport throw site(s). Clipboard interception remains active.")

    try:
        FILE_PATH.write_text("".join(lines), encoding="utf-8")
        return 0
    except Exception as e:
        logger.error(f"Failed to write {FILE_PATH}: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())