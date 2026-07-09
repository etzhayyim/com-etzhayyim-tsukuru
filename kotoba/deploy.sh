#!/usr/bin/env bash
# tsukuru 作 — kotoba deploy
# ADR-2605202800 · migration plan Phase 3
#
# Ingests schema-shaped seed datoms (factories + a worked production-order) into a
# running kotoba node and (optionally) builds the langgraph WASM actor (4-handler
# graph). Writes to the canonical Datom journal require an authorized operator
# session token (no-server-key posture, G15). Without KOTOBA_TOKEN the ingest is a
# dry-run. Replaces the legacy `etzhayyim build` / `etzhayyim deploy` path (G6).
#
# Usage:
#   KOTOBA_URL=http://127.0.0.1:8077 KOTOBA_TOKEN=<at-session-jwt> ./deploy.sh
set -euo pipefail

KOTOBA_URL="${KOTOBA_URL:-http://127.0.0.1:8077}"
GRAPH="${TSUKURU_GRAPH:-com.etzhayyim.tsukuru}"
ACTOR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> tsukuru kotoba deploy → ${KOTOBA_URL} (graph ${GRAPH})"

if ! curl -fsS -m 5 "${KOTOBA_URL}/health" >/dev/null 2>&1; then
  echo "!! kotoba node not reachable at ${KOTOBA_URL} — start it with: kotoba serve" >&2
  exit 1
fi

# 0.5 manufacturer registry projection (representative R0; 460-DID live path is G11-gated)
echo "--> factory registry ingest (:factory/* datoms)"
python3 "${ACTOR_DIR}/kotoba/ingest_factories.py" --url "${KOTOBA_URL}" --graph "${GRAPH}" \
  $([[ -z "${KOTOBA_TOKEN:-}" ]] && echo --dry-run)

# 0.6 candidate registry projection (public-directory research data, candidates.edn).
# Deliberately writes to its own graph, never ${GRAPH} — candidates.edn holds
# non-consented, non-onboarded company research (candidate:manufacturer-directory/...
# DIDs, :factory/sourcing :public-directory, :factory/labor-provenance :unknown), so it
# must never land in the same graph as consented did:web members (G10). See
# ingest_candidates.cljc's assert-not-member-graph! for the hard guard.
CANDIDATES_GRAPH="${TSUKURU_CANDIDATES_GRAPH:-com.etzhayyim.tsukuru.candidates}"
echo "--> candidate registry ingest (:factory/* datoms, isolated graph ${CANDIDATES_GRAPH})"
bb "${ACTOR_DIR}/kotoba/ingest_candidates.cljc" --url "${KOTOBA_URL}" --graph "${CANDIDATES_GRAPH}" \
  $([[ -z "${KOTOBA_TOKEN:-}" ]] && echo --dry-run)

if [[ -z "${KOTOBA_TOKEN:-}" ]]; then
  echo "--> KOTOBA_TOKEN unset → DRY RUN (no writes). Set an operator AT-session-JWT to ingest."
  python3 "${ACTOR_DIR}/kotoba/ingest_mcp.py" --url "${KOTOBA_URL}" --graph "${GRAPH}" --dry-run
else
  echo "--> ingesting seed datoms via MCP (operator token present)"
  KOTOBA_TOKEN="${KOTOBA_TOKEN}" python3 "${ACTOR_DIR}/kotoba/ingest_mcp.py" \
    --url "${KOTOBA_URL}" --graph "${GRAPH}" --via mcp
  echo "--> sealing hot arrangement (kotoba commit)"
  kotoba --url "${KOTOBA_URL}" --token "${KOTOBA_TOKEN}" commit
fi

echo "--> langgraph actor build (componentize-py)"
if command -v componentize-py >/dev/null 2>&1; then
  ( cd "${ACTOR_DIR}/py" && componentize-py -w kotoba-actor componentize agent -o agent.wasm )
  echo "    built py/agent.wasm — deploy via the node's invoke.run with an operator token"
else
  echo "    (componentize-py absent — skipping wasm build)"
fi

echo "==> done"
