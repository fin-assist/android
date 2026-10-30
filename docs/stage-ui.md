# Stage UI tests (FIN-386)

The separate `Stage UI tests (manual)` workflow has only `workflow_dispatch`: the owner starts a rare run
from GitHub Actions. It is not called by another workflow and has no push, PR, schedule or completion trigger.
Ordinary CI compiles the harness and runs its unit tests; it never executes the stage UI suite automatically.
The existing automatic mock suite keeps its workflow, source tree and run policy. Original Qase IDs are retained;
the shared map additionally names the stage-only process-death case60.

## Harness and boundaries

`stageE2e` installs as `ru.finassist.pf.stage.e2e`, alongside ordinary `stageDebug`/`stageRelease`. It uses
the real `:core:network` HTTP/SSE adapters and fixed `https://stage.plainfin.ru/`, system TLS trust and the real
clock. Existing synthetic asset files are reused only by this variant; `:mock:backend` code/dependency is absent. Local test flags, the bundled synthetic document picker and closed fault
controls live only in `app/src/stageE2e`. Remote Config, MyTracker, Tracer and HTTP logging are disabled in that
APK. Ordinary stage and production variants retain their existing bindings and contain no controls.

The network module accepts a default-empty set of application interceptors; only the test source set contributes
the stage fault interceptor. This does not change server authorization, quotas, data calculations or provider state.
Client error/offline/latency and SSE injections are counted separately from real requests. A synthetic HTTP error
does not establish that the real backend enforces that error. A withheld verification event does not establish
server-side expiration. Unsupported controls fail the flow rather than silently doing nothing.

The only accepted account receipt is a canonical run UUID and service-local synthetic phones `+7000000xxxx`.
The host writes it to private app storage with `run-as`, after clearing only the test package. Normal hook actions
cannot choose another host, arbitrary HTTP bodies, real phone numbers or another account. Registration correlation
keeps verification/registration tokens in RAM; the private evidence file contains only the run identity, counters
and successful synthetic registration identities, never credentials.

## Running and ownership

Use a disposable emulator with `adb`, Maestro **2.11.0**, JDK21+ and the Android SDK on PATH. The workflow creates
a fresh API34 emulator with Moscow timezone. Local example (explicit UI authorization is required by project process):

```sh
python3 scripts/stage-ui.py --inventory-only
python3 scripts/stage-ui.py --case qase-8
python3 scripts/stage-ui.py --no-build
```

The first command only validates the inventory; it makes no network/device calls. `--prepare-report` writes the full
not-run report before CI builds/tools/emulator preparation. Every test subprocess has an actual process-group timeout;
each public HTTP call has a timeout and a bounded body. The workflow also limits preparation, execution and reporting.
`--serial` accepts only disposable emulator serials, never a connected personal phone. `--case` produces an explicitly
partial run and cannot establish full acceptance. `--retain-owned` retains only newly registered test accounts and
is recorded as retention, not successful restoration.

Feature scenarios keep the first session: a closed test precondition performs actual consent/phone/registration APIs,
imports the baseline OFX and waits for public analytics readiness, then continues with the resulting encrypted session.
It does not register an account on the host and ask the UI to authenticate it again. A candidate that turns out to be
an existing identity is refused before adopting its session or reading/importing its data. Registration case2 retains
the actual registration screens. Only a correlated successful registration grants cleanup ownership; a randomly
allocated synthetic phone alone grants no data access or deletion authority.

Dedicated existing-account authentication and native cases have separate prepared identities. Independent first-session
cases run before them, allowing those preconditions to age naturally. There is no automatic five-minute wait or bypass
of the real production phone busy rule. An existing-login case selected alone can report an unready precondition; that
is not silently converted to an artificial user wait or an accepted test. A repeated login while the provider's phone
is still busy remains a distinct production behavior, not a prerequisite added to every feature test.

Cleanup normally uses that first session too: a closed control verifies the actual profile phone and user ID against
the run's successful registration receipt before real DELETE. Logout cases can defer cleanup until suite end; no
automatic phone-limit wait is introduced. Host-owned preconditions use their own verified identity/session. Invalid
tokens alone do not prove deletion; a fallback reconciles only an owned synthetic identity. Every cleanup failure is retained in the report
and prevents full acceptance. No SQL, deployment, global fault switch, old-account deletion or private statement is used.

## Inventory and reporting

[`stage-case-inventory.md`](stage-case-inventory.md) maps the **55 Maestro flows and one native case** to their original
Qase IDs. [`../scripts/stage-cases.json`](../scripts/stage-cases.json) is the executable inventory. `ready` means an
authored runnable candidate, not a passed test. Known missing provider/state mechanisms are explicitly `blocked`.
The additional process-death case60 has a host checkpoint: successful real Ask accepted, response still held, Home,
`am kill`, confirmed disappearance of the client PID, then restored UI and actual history with exactly one question.
The server keeps running; no automatic duplicate POST or second charge is permitted. The legacy restored-key seed
does not prove this boundary.

Reports are separate from mock reports, in `build/stage-ui/`: JSON, JUnit, execution logs, screenshots and UI hierarchies
for failures. Text materials redact credentials/JWTs and synthetic phones; unknown binary files/raw archives are not
published. Native picker coverage retains the original ActivityResult interception boundary, not a claim to drive the
system DocumentsUI. Both exit status and a fresh exact XML result must pass; skipped, absent, malformed, failed and
blocked cases are failures. Per-case client counters retain real/injected provenance. No blocked case disappears from
the full denominator, and a partial run cannot have `full_acceptance:true`.

The manual workflow publishes a separate stage Qase run through the existing token when configured, and always uploads
the stage artifacts, including the initialized report if preparation failed. Original mapping identities are preserved;
the JSON report additionally contains any requirements not yet assigned an original Qase case. The expected FIN-362
fake-model scenario hash is recorded as an expectation; a source file or old acceptance does not establish the current
running provider revision. Real UI assertions demonstrate only the behavior actually observed in this run.
