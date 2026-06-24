#!/usr/bin/env python3
"""tsukuru 作 — manufacturer registry ingest (legacy → kotoba :factory/* Datoms).

ADR-2605202800, migration plan Phase 3. The legacy etzhayyim-era registry held 460+
manufacturer DIDs across 30+ countries in the AT collections:

    com.etzhayyim.apps.tsukuru.manufacturer        (active)
    com.etzhayyim.apps.tsukuru-api.manufacturer    (historical, read-compat)

This script projects those records into kotoba `:factory/*` Datoms (G6). At R0 it
reads from the representative seed.edn; the live path (reading the 460-DID legacy
collections from the PDS and asserting via MCP) is G11-gated and operator-bound
(no-server-key, G15) — left explicit, never a silent no-op.

Usage:
    python3 ingest_factories.py [--url http://127.0.0.1:8077] [--dry-run]
"""
from __future__ import annotations

import argparse
import os
import sys

from ingest_mcp import _top_level_entities  # reuse the EDN entity splitter

SEED = os.path.join(os.path.dirname(__file__), "seed.edn")
LEGACY_COLLECTIONS = (
    "com.etzhayyim.apps.tsukuru.manufacturer",
    "com.etzhayyim.apps.tsukuru-api.manufacturer",  # read-compat
)


def _factory_entities(raw: str):
    return [e for e in _top_level_entities(raw) if ":factory/did" in e]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8077")
    ap.add_argument("--graph", default="com.etzhayyim.tsukuru")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    with open(SEED, encoding="utf-8") as f:
        factories = _factory_entities(f.read())

    print(f"   {len(factories)} representative factory entities → {args.graph}:factory/*")
    print(f"   legacy source collections (live path, G11-gated): {', '.join(LEGACY_COLLECTIONS)}")
    if args.dry_run or not os.environ.get("KOTOBA_TOKEN"):
        print("   DRY RUN — no writes. R0 seed only; 460-DID live projection is operator-gated (G11/G15).")
        return 0

    print("   live factory projection requested — wire PDS read + MCP kotoba_datom_create before use (G11).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
