# Instructions for code agents

## Review guidelines

- Write all review comments in Russian (summary and inline comments). Code identifiers, API field names and
  quotes from the code stay as they are.
- Contract: `api.md` / `openapi.yaml` in the claude.ai project «Финансовый консультант»; in this repo the
  rules are mirrored in KDoc of `core/api` and `core/network`. Toggles: `docs/flags.md`. Modules: `docs/modules.md`.
- Report only verified problems with a concrete failure scenario; skip style nits.

## Code

- Code, KDoc and commit messages are in English; user-facing strings are in Russian.
- Each feature is `:api` + `:impl`; an `:impl` depends on other features only through their `:api`
  (enforced by `pf.module.rules`).
