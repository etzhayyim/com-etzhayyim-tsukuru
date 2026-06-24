# tsukuru — etzhayyim→etzhayyim rename items (Phase 5, GATED — DO NOT EXECUTE STANDALONE)

> **GATE (P0b)**: これらの rename は **法人登記後の kotodama atomic cutover wave**
> (ADR-2605214000 §3 + ADR-2605215000 §4) の一部として **単一 PR で**実行する。
> 部分実行は runtime を壊す（env var + config dir + DNS suffix + WIT package が相互依存）。
> repo-root `CLAUDE.md` § "Do Not" の「Do not rename `etzhayyim-*` identifiers … outside the
> Step 8 cutover wave」に従う。本ファイルは **項目の登録のみ**で、実行はしない。

Phase 2–4 (manifest.edn / cells / lex / kotoba / py) は既に landed（非ゲート）。
残るは命名 cutover のみ。migration plan: `90-docs/260602-tsukuru-kotoba-native-migration-plan.md` Phase 5。

## A. WIT packages (`etzhayyim:` → `etzhayyim:`)

| 現 (legacy) | cutover 後 |
|---|---|
| `etzhayyim:tsukuru@0.1.0` | `etzhayyim:tsukuru@0.1.0` |
| `etzhayyim:tsukuru-process-registry@1.0.0` | `etzhayyim:tsukuru-process-registry@1.0.0` |
| `etzhayyim:tsukuru-manufacturer-registry@1.0.0` | `etzhayyim:tsukuru-manufacturer-registry@1.0.0` |
| `etzhayyim:tsukuru-trade-compliance@1.0.0` | `etzhayyim:tsukuru-trade-compliance@1.0.0` |
| `etzhayyim:tsukuru-production-order@1.0.0` | `etzhayyim:tsukuru-production-order@1.0.0` |

(source-of-truth: `manifest.edn` `:actor/legacy :wit-packages`)

## B. App / contract paths (`com/etzhayyim` → `com/etzhayyim`)

| 現 (legacy) | cutover 後 |
|---|---|
| `60-apps/etzhayyim-project-tsukuru/` | `60-apps/etzhayyim-project-tsukuru/` |
| `00-contracts/lexicons/com/etzhayyim/apps/tsukuru/` | `00-contracts/lexicons/com/etzhayyim/tsukuru/` |
| `00-contracts/bpmn/com/etzhayyim/tsukuru/` | `00-contracts/bpmn/com/etzhayyim/tsukuru/` |
| `00-contracts/catalogs/com/etzhayyim/tsukuru/` | `00-contracts/catalogs/com/etzhayyim/tsukuru/` |
| `00-contracts/examples/com/etzhayyim/tsukuru/` | `00-contracts/examples/com/etzhayyim/tsukuru/` |

## C. Build/deploy + runtime

| 現 (legacy) | cutover 後 |
|---|---|
| `etzhayyim build` / `etzhayyim deploy` | `kotoba/deploy.sh` (already landed, Phase 3) |
| `runtime: k8s-langserver` + `legacyExecutionTier: T1` | WASM langgraph cell (kotoba :8077) — Phase 4 landed |
| `convoSystemPrompt` (`agent.chat` / external) | Murakumo KotobaLLM 127.0.0.1:4000 — Phase 4 landed |
| nanoid host `tsukr8u0.etzhayyim.com` (`0ljdfw8u` deprecated) | retain `tsukr8u0`; purge `0ljdfw8u` refs |

## D. AT collections (read-compat retained)

| 現 (legacy) | cutover 後 |
|---|---|
| `com.etzhayyim.apps.tsukuru.manufacturer` (active) | `com.etzhayyim.tsukuru.factory` (kotoba `:factory/*`) |
| `com.etzhayyim.apps.tsukuru-api.manufacturer` (historical) | read-compat only; project → `:factory/*` then archive |

> 460+ factory DID の live projection は `kotoba/ingest_factories.py` の operator-gated
> path (G11/G15)。schema は Phase 3 で landed、live ingest は登記後。

## E. Decommission after cutover verified

- `actor-manifest.jsonld` (Gen-1 manifest) → `manifest.edn` が canonical になった後に削除
- legacy Cypher / `G()` builder / RisingWave 参照 → kotoba-kqe Datalog に置換済み (Phase 3) を確認後に撤去

## Acceptance (Phase 5 done)

- [ ] kotodama atomic wave PR に本項目を merge（単独 PR にしない）
- [ ] `grep -r "etzhayyim:" 20-actors/tsukuru` が 0
- [ ] `60-apps/etzhayyim-project-tsukuru` が存在しない
- [ ] `actor-manifest.jsonld` 削除 + `manifest.edn` のみ
- [ ] root CLAUDE.md Tier-B roster に tsukuru 行を正式追加
