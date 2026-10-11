#!/usr/bin/env python3
"""
Static manifest surgery: Removes every permission except the allow-list.
Also strips Foreground Service Type tokens tied to removed permissions (Android 14+ safety).
"""
import json
import logging
import re
import sys
from pathlib import Path
from typing import Set

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger(__name__)

ALLOWLIST: Set[str] = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.WAKE_LOCK",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.MODIFY_AUDIO_SETTINGS",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
}

FGS_TYPE_MAP = {
    "android.permission.CAMERA": "camera",
    "android.permission.RECORD_AUDIO": "microphone",
    "android.permission.ACCESS_FINE_LOCATION": "location",
    "android.permission.ACCESS_COARSE_LOCATION": "location",
    "android.permission.BODY_SENSORS": "health",
}

PERM_PATTERN = re.compile(
    r'<(?:uses-permission|uses-permission-sdk-23)\s+[^>]*android:name="([^"]+)"[^>]*/>',
    re.DOTALL
)


def strip_permissions(decoded_path: Path, report_path: Path) -> int:
    manifest_path = decoded_path / "AndroidManifest.xml"
    if not manifest_path.exists():
        logger.error(f"AndroidManifest.xml not found at {manifest_path}")
        return 1

    try:
        content = manifest_path.read_text(encoding="utf-8")
    except Exception as e:
        logger.error(f"Failed to read manifest: {e}")
        return 1

    removed_list = []

    def replace_perm(match: re.Match) -> str:
        perm_name = match.group(1)
        if perm_name not in ALLOWLIST:
            removed_list.append(perm_name)
            return ""
        return match.group(0)

    content = PERM_PATTERN.sub(replace_perm, content)

    # Android 14+ safety: strip FGS type tokens tied to removed permissions
    for perm, fgs_type in FGS_TYPE_MAP.items():
        if perm not in ALLOWLIST:
            def strip_fgs(match: re.Match, t: str = fgs_type) -> str:
                types = match.group(1).split("|")
                filtered = [x.strip() for x in types if x.strip() != t]
                if not filtered:
                    return ""
                return f'android:foregroundServiceType="{"|".join(filtered)}"'
            
            content = re.sub(rf'android:foregroundServiceType="([^"]*\b{fgs_type}\b[^"]*)"', strip_fgs, content)

    try:
        manifest_path.write_text(content, encoding="utf-8")
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(
            json.dumps({"removed": sorted(removed_list), "kept": sorted(ALLOWLIST)}, indent=2),
            encoding="utf-8"
        )
        logger.info(f"Removed {len(removed_list)} permissions, kept {len(ALLOWLIST)}.")
        logger.info(f"Wrote report to {report_path}")
        return 0
    except Exception as e:
        logger.error(f"Failed to write manifest or report: {e}")
        return 1


def main() -> int:
    if len(sys.argv) < 2:
        logger.error("Usage: python3 strip_permissions.py <decoded_apk_dir> [output_report.json]")
        return 1
        
    decoded_dir = Path(sys.argv[1])
    report_file = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("build/permissions_report.json")
    
    return strip_permissions(decoded_dir, report_file)


if __name__ == "__main__":
    sys.exit(main())