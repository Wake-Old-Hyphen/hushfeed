#!/usr/bin/env python3
"""
strip_permissions.py
Performs static manifest surgery on a decoded APK to remove all permissions
except the allow-list. This is a privacy hardening technique that removes
permission entries from the binary manifest entirely.

Usage: python3 strip_permissions.py <path_to_decoded_apk>
Example: python3 strip_permissions.py build/decoded
"""

import sys
import os
import re

# Permissions to KEEP (everything else gets deleted)
ALLOWLIST = {
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


def strip_permissions(decoded_path):
    manifest_path = os.path.join(decoded_path, "AndroidManifest.xml")

    if not os.path.exists(manifest_path):
        print(f"❌ ERROR: AndroidManifest.xml not found at {manifest_path}")
        sys.exit(1)

    with open(manifest_path, "r", encoding="utf-8") as f:
        content = f.read()

    original_length = len(content)
    removed_count = 0
    removed_list = []

    # Pattern to match <uses-permission ... /> and <uses-permission-sdk-23 ... />
    # Captures the permission name
    perm_pattern = re.compile(
        r'<(?:uses-permission|uses-permission-sdk-23)\s+[^>]*android:name="([^"]+)"[^>]*/>',
        re.DOTALL
    )

    def replace_perm(match):
        nonlocal removed_count, removed_list
        perm_name = match.group(1)
        if perm_name not in ALLOWLIST:
            removed_count += 1
            removed_list.append(perm_name)
            return ""  # Remove the entire tag
        return match.group(0)  # Keep it

    content = perm_pattern.sub(replace_perm, content)

    # Also strip foregroundServiceType tokens for removed permissions (Android 14+ safety)
    # If a service references a removed FGS type, remove that type token
    removed_fgs_types = []
    
    # Map permissions to their foregroundServiceType tokens
    fgs_type_map = {
        "android.permission.CAMERA": "camera",
        "android.permission.RECORD_AUDIO": "microphone",
        "android.permission.ACCESS_FINE_LOCATION": "location",
        "android.permission.ACCESS_COARSE_LOCATION": "location",
        "android.permission.BODY_SENSORS": "health",
    }

    for perm, fgs_type in fgs_type_map.items():
        if perm not in ALLOWLIST and perm not in [p for p in removed_list]:
            # If the permission wasn't in the manifest but FGS type might be
            pass
        if perm not in ALLOWLIST:
            # Strip this FGS type from foregroundServiceType attributes
            # This handles: android:foregroundServiceType="camera|microphone|dataSync"
            def strip_fgs(match):
                nonlocal removed_fgs_types
                attr_value = match.group(1)
                types = attr_value.split("|")
                filtered_types = [t.strip() for t in types if t.strip() != fgs_type]
                removed_fgs_types.append(fgs_type)
                if not filtered_types:
                    return ""  # Remove entire attribute if no types left
                return f'android:foregroundServiceType="{"|".join(filtered_types)}"'
            
            content = re.sub(
                rf'android:foregroundServiceType="([^"]*\b{fgs_type}\b[^"]*)"',
                strip_fgs,
                content
            )

    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write(content)

    new_length = len(content)
    bytes_removed = original_length - new_length

    print(f"✅ Permission stripping complete!")
    print(f"   Removed: {removed_count} permissions ({bytes_removed} bytes)")
    print(f"   Kept:    {len(ALLOWLIST)} permissions")
    print(f"   FGS types stripped: {len(set(removed_fgs_types))}")
    print()
    print("   🗑️  Removed permissions:")
    for p in sorted(removed_list):
        print(f"      ❌ {p}")
    print()
    print("   ✅ Kept permissions:")
    for p in sorted(ALLOWLIST):
        print(f"      ✔️  {p}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python3 strip_permissions.py <path_to_decoded_apk>")
        sys.exit(1)

    strip_permissions(sys.argv[1])