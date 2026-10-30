# Stage UI suite (FIN-386)

Use the host stage runner documented in `docs/e2e.md`. Do not run this folder directly:
`clear_state.yaml` intentionally only stops the app. The runner clears the stageE2e package,
creates owned synthetic accounts/data and then installs the private `files/stage-run.json`
receipt **before each root case**. Clearing again from Maestro would erase that receipt.

This is a separate suite for `ru.finassist.pf.stage.e2e`; `.maestro/` stays unchanged.
Every source case remains mapped in `scripts/stage-cases.json`, including blocked cases.
A blocked flow begins with an unconditional failed assertion so direct execution cannot
turn an unsupported scenario into a green result. The full host report must be red while
any blocked/missing/failed case remains. `ready` means authored and selected for execution;
it never means passed without a fresh device report.

The real HTTPS stage remains the source for account/session, OFX import, persisted history,
operations, analytics and assistant state. Client fault controls are separately labelled
`client-injected`; they do not prove a server/provider outage, TTL, quota or durable failure.
See `docs/stage-case-inventory.md` for all mappings, provider revision and limitations.

No auth token, passcode or private statement belongs in the environment values or source.
The app's run receipt carries only a UUID and a synthetic phone; tokens are acquired over
the real authentication API and stay in the ordinary encrypted token store.
