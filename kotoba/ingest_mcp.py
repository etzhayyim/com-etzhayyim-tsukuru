#!/usr/bin/env python3
"""tsukuru 作 — ingest seed.edn into a live kotoba node via MCP.

ADR-2605202800, migration plan Phase 3. Flattens each seed entity map into
(graph, subject, predicate, object) datoms and asserts them via the
`kotoba_datom_create` MCP tool; `kotoba commit` seals them. Writes require an
operator AT-session JWT (no-server-key posture, G15). --dry-run parses + counts
datoms only (no writes, no LLM; G5 untouched).

Usage:
    python3 ingest_mcp.py [--url http://127.0.0.1:8077] [--graph com.etzhayyim.tsukuru] [--dry-run]
"""
from __future__ import annotations

import argparse
import os
import sys

SEED = os.path.join(os.path.dirname(__file__), "seed.edn")


def _strip_comments(s: str) -> str:
    out = []
    in_str = False
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if c == '"' and s[i - 1] != "\\":
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == ";":
            while i < n and s[i] != "\n":
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _top_level_entities(s: str):
    """Yield each top-level {...} map literal inside the outer [ ... ] vector."""
    s = _strip_comments(s)
    start = s.find("[")
    if start < 0:
        return
    depth = 0
    buf = []
    in_str = False
    for c in s[start + 1:]:
        if in_str:
            buf.append(c)
            if c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
            buf.append(c)
            continue
        if c == "{":
            depth += 1
            buf.append(c)
        elif c == "}":
            depth -= 1
            buf.append(c)
            if depth == 0:
                yield "".join(buf).strip()
                buf = []
        elif depth > 0:
            buf.append(c)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8077")
    ap.add_argument("--graph", default="com.etzhayyim.tsukuru")
    ap.add_argument("--via", default="mcp")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    with open(SEED, encoding="utf-8") as f:
        raw = f.read()
    entities = list(_top_level_entities(raw))
    datoms = sum(e.count(" :") + (1 if e.startswith("{:") else 0) for e in entities)

    print(f"   parsed {len(entities)} entities (~{datoms} datoms) from seed.edn → {args.graph}")
    if args.dry_run or not os.environ.get("KOTOBA_TOKEN"):
        print("   DRY RUN — no writes. Set KOTOBA_TOKEN (operator AT-session JWT) to ingest.")
        return 0

    # live ingest path (operator token present) — assert via MCP kotoba_datom_create.
    # R0 scaffold — wire to the live MCP endpoint when the operator session is
    # provisioned (G11 gates real outward writes). Kept explicit to avoid silent no-op.
    print("   live ingest requested — implement MCP kotoba_datom_create wiring before use (G11).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
