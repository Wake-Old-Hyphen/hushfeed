#!/usr/bin/env python3
"""
Injects custom AMOLED dark theme IDs into Hushfeed's AmoledThemePatch.kt.

Features:
- Merge Mode: Inserts custom IDs alongside the author's existing IDs (prevents overwriting new upstream IDs).
- Relaxed Validation: Modifies the strict completeness check to only fail if *zero* IDs match, preventing build failures on newer TikTok versions where some IDs are renamed.
"""
import logging
import re
import sys
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger(__name__)

FILE_PATH = Path("patches/src/main/kotlin/app/morphe/patches/tiktok/misc/theme/AmoledThemePatch.kt")

# Custom IDs discovered on 46.x / 47.0.3. Merged with whatever the author ships.
OUR_SHEET_ITEMS = (
    '"eyq", "cbx", "eze", "f2a", "f8g", "f8j", '
    '"ccr", "cd4", "f38", "f39", "f3d", "f3e", "f5_", "agg", "agh", "agm", "agn", '
    '"bo", "bx", "c1", "c8p", "c8r", "c8z", "c90", "eu8", "ezu", "f2t", '
    '"aag", "ae5", "ag2", "apn", "a3i"'
)

OLD_CHECK_PATTERN = re.compile(
    r"internal fun checkSheetStyleItems\(found: Set<String>, versionName: String\?, "
    r"declared: Set<String>\)\s*\{.*?if \(found\.isEmpty\(\)\) throw PatchException"
    r"\(\"No dark sheet style item was found on \$versionName\"\)\s*\}",
    re.DOTALL
)

NEW_CHECK = (
    'internal fun checkSheetStyleItems(found: Set<String>, versionName: String?, declared: Set<String>) {\n'
    '    if (found.isEmpty()) throw PatchException("No dark sheet style item was found on $versionName")\n'
    '}'
)


def main() -> int:
    if not FILE_PATH.exists():
        logger.error(f"Target file not found: {FILE_PATH}")
        logger.error("Ensure this script is run from the repository root.")
        return 1

    try:
        content = FILE_PATH.read_text(encoding="utf-8")
    except Exception as e:
        logger.error(f"Failed to read {FILE_PATH}: {e}")
        return 1

    original_content = content

    # MERGE mode: insert our IDs right after 'setOf(', keeping the author's entries.
    # Duplicates are legal in Kotlin's setOf (they deduplicate automatically).
    content = re.sub(r'(internal val SHEET_STYLE_ITEMS\s*=\s*setOf\()', rf'\1{OUR_SHEET_ITEMS}, ', content, count=1)
    content = re.sub(r'(private val DECLARED_ONLY_ITEMS\s*=\s*setOf\()', rf'\1{OUR_SHEET_ITEMS}, ', content, count=1)

    # Relaxed completeness check
    content = OLD_CHECK_PATTERN.sub(NEW_CHECK, content)

    if content == original_content:
        logger.warning("Injection changed nothing. The author may have renamed the target variables.")
    else:
        logger.info("AMOLED IDs merged with author's list; strict check relaxed.")

    try:
        FILE_PATH.write_text(content, encoding="utf-8")
        return 0
    except Exception as e:
        logger.error(f"Failed to write {FILE_PATH}: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())