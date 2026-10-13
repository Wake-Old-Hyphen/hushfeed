#!/usr/bin/env python3
"""
Applies Nai64ExtraPatches_Options.yaml to build/options.json.

Features:
- Disables specified Hushfeed patches to prevent double-hooking.
- Applies custom options to Hushfeed, Nai64, and kveld9 patches.
- Safe key matching: falls back to the single available option if the exact key is missing.
- Auto-wraps scalar values into lists if the target option expects a list.
"""
import json
import logging
import os
import re
import sys
from typing import Any, Dict, List

import yaml

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger(__name__)


def normalize_key(key: str) -> str:
    """Normalize a string for case-insensitive, punctuation-free comparison."""
    return re.sub(r"[^a-z0-9]", "", (key or "").lower())


def apply_option_value(existing_options: Dict[str, Any], key: str, new_value: Any) -> None:
    """Safely apply a new value to an existing option, respecting its original data structure."""
    current = existing_options[key]
    if isinstance(current, dict) and "value" in current:
        inner = current["value"]
        current["value"] = [new_value] if (isinstance(inner, list) and not isinstance(new_value, list)) else new_value
    elif isinstance(current, list) and not isinstance(new_value, list):
        existing_options[key] = [new_value]
    else:
        existing_options[key] = new_value


def apply_requested_options(patch_name: str, patch_data: Dict[str, Any], requested: Dict[str, Any]) -> None:
    """Apply option values from the YAML to a specific patch with safe key matching."""
    existing = patch_data.get("options") or {}
    
    for key, new_value in (requested.get("options") or {}).items():
        if key in existing:
            apply_option_value(existing, key, new_value)
            logger.info(f"  -> {patch_name}: {key} = {new_value!r}")
            continue
        
        norm_key = normalize_key(key)
        match = next((ek for ek in existing if normalize_key(ek) == norm_key), None)
        if match:
            apply_option_value(existing, match, new_value)
            logger.info(f"  -> {patch_name}: {match} (matched '{key}') = {new_value!r}")
            continue
        
        if len(existing) == 1:
            only_key = next(iter(existing))
            apply_option_value(existing, only_key, new_value)
            logger.warning(f"  -> {patch_name}: Key '{key}' not found; applied to its single option '{only_key}' = {new_value!r}")
        else:
            logger.error(f"  -> {patch_name}: Key '{key}' not found. Available keys: {list(existing.keys())}")
            
    patch_data["options"] = existing


def main() -> int:
    try:
        with open("build/options.json", "r", encoding="utf-8") as f:
            options: List[Dict[str, Any]] = json.load(f)
        with open("Nai64ExtraPatches_Options.yaml", "r", encoding="utf-8") as f:
            cfg: Dict[str, Any] = yaml.safe_load(f) or {}
    except FileNotFoundError as e:
        logger.error(f"Missing required file: {e.filename}")
        return 1
    except Exception as e:
        logger.error(f"Failed to load configuration files: {e}")
        return 1

    requested_nai64 = {p["name"].lower(): p for p in cfg.get("patches", [])}
    requested_kveld9 = {p["name"].lower(): p for p in cfg.get("kveld9_patches", [])}
    disabled_hushfeed = {normalize_key(x) for x in cfg.get("hushfeed_disabled", [])}
    hushfeed_options = {normalize_key(x.get("name", "")): x for x in cfg.get("hushfeed_options", [])}

    extra_hushfeed_disabled = {normalize_key(x) for x in os.environ.get("FORCE_DISABLE_HUSHFEED", "").split(",") if x.strip()}
    extra_nai64_disabled = {normalize_key(x) for x in os.environ.get("FORCE_DISABLE_NAI64", "").split(",") if x.strip()}
    
    disabled_hushfeed |= extra_hushfeed_disabled
    if extra_hushfeed_disabled:
        logger.info(f"Env-forced Hushfeed disables: {sorted(extra_hushfeed_disabled)}")
    if extra_nai64_disabled:
        logger.info(f"Env-forced Nai64 disables: {sorted(extra_nai64_disabled)}")

    for i, bundle in enumerate(options):
        patches_dict = bundle.get("patches", {})

        if i == 0:  # Hushfeed
            for name, data in patches_dict.items():
                norm_name = normalize_key(name)
                if norm_name in disabled_hushfeed:
                    data["enabled"] = False
                    logger.info(f"  -> Hushfeed disabled: {name}")
                else:
                    data["enabled"] = True
                
                req = hushfeed_options.get(norm_name)
                if req:
                    apply_requested_options(name, data, req)

        elif i == 1:  # Nai64
            for name, data in patches_dict.items():
                data["enabled"] = False
                norm_name = normalize_key(name)
                
                if norm_name in extra_nai64_disabled:
                    logger.info(f"  -> Nai64 disabled (env-forced): {name}")
                    continue
                    
                req = requested_nai64.get(name.lower())
                if not (req and req.get("enabled", False)):
                    continue
                    
                data["enabled"] = True
                apply_requested_options(name, data, req)

            found_patches = {n.lower() for n in patches_dict}
            for req_name in requested_nai64:
                if req_name not in found_patches:
                    logger.warning(f"Requested Nai64 patch not present in bundle: '{req_name}'")

        elif i == 2:  # kveld9: allowlist semantics — universals only
            for name, data in patches_dict.items():
                data["enabled"] = False
                req = requested_kveld9.get(name.lower())
                if not (req and req.get("enabled", False)):
                    continue
                data["enabled"] = True
                apply_requested_options(name, data, req)

            found_patches = {n.lower() for n in patches_dict}
            for req_name in requested_kveld9:
                if req_name not in found_patches:
                    logger.warning(f"Requested kveld9 patch not present in bundle: '{req_name}'")

    try:
        with open("build/options.json", "w", encoding="utf-8") as f:
            json.dump(options, f, indent=2)
        logger.info("Options configured successfully via Nai64ExtraPatches_Options.yaml")
        return 0
    except Exception as e:
        logger.error(f"Failed to write build/options.json: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())