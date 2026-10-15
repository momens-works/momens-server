# Agent Guide

This document is the **stable entry point** for AI coding agents (Codex and Claude
Code) working in `momens-server`. It holds durable project context and agent working
rules. Detailed or frequently changing project decisions live in other docs — start
here and explore outward via the "Documentation Map" section below.

Do not put confirmed-decision tables, dependency matrices, module lists, migration
plans, or other volatile project state in this file.

## Working Principles

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" -> "Write tests for invalid inputs, then make them pass"
- "Fix the bug" -> "Write a test that reproduces it, then make it pass"
- "Refactor X" -> "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

```text
1. [Step] -> verify: [check]
2. [Step] -> verify: [check]
3. [Step] -> verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work")
require constant clarification.

## Repository Roles In The Wider System

| Repository | Role |
| --- | --- |
| `teams` | Product requirements, ADRs, glossary, product language |
| `momens-api` | Legacy Go/Gin user-facing API to be replaced |
| `momens-server` | New Java Spring product API server |
| `momens-worker` | External source ingestion and curation worker |
| `momens-retrieval` | Java Spring retrieval read-model server |
| `k8s` | Kubernetes and infrastructure definitions |

## Architecture Intent

`momens-server` should be one deployable Spring Boot API application. It should
use Gradle multi-module boundaries and Spring Modulith verification to keep
internal modules explicit.

Do not split into microservices.

Detailed architecture rules live in `docs/rules/architecture.md`.

## Working Rules

- Before writing or reviewing docs and comments, apply
  [the shared documentation rules](docs/rules/documentation.md).
- Do not invent dependencies. Add only dependencies confirmed in docs or by the user.
- Do not infer unsettled decisions. If a rule is not documented, ask the team or update
  the appropriate project doc.
- Do not commit `.env`, `.idea`, `*.iml`, real secrets, or local-only config.
- Do not use private submodules or private repositories as secret stores.
- Keep this file and other agent-only guidance in English.
- Prefer updating shared human-facing docs under `docs/` in Korean; AI agents also
  read those docs, but there is no separate AI documentation tree.

## Documentation Map

This file (`AGENTS.md`) is the single source of truth for agent guidance and is read by
both Codex and Claude Code. `CLAUDE.md` is a one-line pointer (`@AGENTS.md`) — do not
duplicate content there.

Start here:

- `README.md` — repository purpose and current scope
- `docs/README.md` — documentation index
- `docs/onboarding.md` — setup and day-to-day workflow

Topic-specific docs:

- `docs/rules/` — standing project-wide rules
- `docs/rules/architecture.md` — modular modulith and package direction
- `docs/rules/git.md` — branch, commit, PR, and merge rules
- `docs/rules/code-conventions.md` — coding style, Spring, DTO, logging, tests
- `docs/rules/persistence.md` — DB, JPA, Flyway, time, identifiers
- `docs/rules/configuration.md` — configuration and secrets
- `docs/rules/observability.md` — metrics, traces, logs, correlation IDs
- `docs/spec/` — server API contracts
- `docs/adr/` — ADR process and exceptional decision records
- `docs/design/` — evolving detailed design, added when needed

Agent skills (same skill set per agent, see `docs/onboarding.md` for the workflow):

- `.codex/skills/` — project-local Codex skills (`sync`, `start-work`, `diff-review`,
  `finish-work`, `migrate-slice`)
- `.claude/skills/` — project-local Claude Code skills (`sync`, `start-work`, `diff-review`,
  `finish-work`, `migrate-slice`)
