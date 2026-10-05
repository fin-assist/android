# Project conventions

- Monorepo of Gradle modules; module rules in `docs/modules.md` are enforced at configuration time — read them before adding a dependency.
- Feature = `:feature:<name>:api` + `:feature:<name>:impl`. Cross-feature dependencies only through `api`.
- Flags: declare in `core/toggles/.../Flags.kt` and document in `docs/flags.md` (CI diff-checks both). Read flags at screen boundaries.
- API contract is `api.md` / `openapi.yaml` from the product project; `code` fields are open strings mapped to enums with an `UNKNOWN` fallback, `enum` fields are closed.
- Money is `Long` kopecks; analytics amounts are signed; operation amounts are non-negative with `kind`.
- Comments: explain non-obvious decisions and contract edge cases, not what the code does.
- Never commit real statements, tokens or keys. Real OFX lives in `app/src/mock/assets/private/` (gitignored).
- Commit messages: imperative, English, scoped (`analytics: add period sheet`).
