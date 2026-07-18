# tsukuru standalone migration

The canonical migration record is `migration.edn`; the repository decision is
`adr/standalone-multirepo.edn`.

`orgs/etzhayyim/com-etzhayyim-tsukuru` now owns the actor implementation, EDN
contracts, tests, and compatibility wire assets. JSON, JSON-LD, and BPMN are
restricted to `wire/` (apart from the DID protocol document at
`.well-known/did.json`). Go, TinyGo, shell runners, and the superseded Python-era
agent directory are not part of the standalone repository.

After the root manifest is pinned to this repository, remove the paths listed in
`:root/cleanup` in `migration.edn` from the legacy root repository.
