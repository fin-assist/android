# Stage UI case inventory (FIN-386)

Source inventory at Android commit `661ff373bfa6357597d1d0e054159d662612b1b2`: **55 Maestro roots + 1 native case = 56**. Qase IDs 53–55 are absent from `scripts/qase-cases.json`; they are not silently counted as implemented cases. The mock tree and original mapping identities remain unchanged; the map adds stage-only case60.

`scripts/stage-cases.json` is the executable selection/mapping contract. `ready` is an authored runnable candidate, not a completed test. A full run reports every source case and every `additional_cases` entry. Blocked, missing, cancelled and timed-out cases cannot become passed or disappear from the denominator.

## Preparation and isolation

- `existing-seeded`: create a fresh owned synthetic phone through the actual stage verification/registration APIs, reject a collision with an existing account, upload the baseline OFX and await completed import and analytics readiness. The only baseline upload is the real imported one; no in-process backend exists.
- `new-empty`: allocate a synthetic candidate without creating a verification request; the UI performs the first Start, registration and upload. A verified existing candidate is refused before its session can be adopted.
- `existing-and-new`: prepare one existing seeded owned account and one fresh unused owned phone; the UI first checks the registration flag on the new phone and then signs into the owned existing one.
- `first-session-seeded` / `first-session-fresh`: actual registration and real OFX preparation inside the test APK, retaining the first encrypted session. No second Phone Start, fixed waiting period or auth bypass. The fresh variant extends only coverage dates to the current Moscow day; operations stay unchanged. Existing-account login prerequisites are separate and execute after independent feature scenarios; an unready prerequisite fails explicitly.
- Before each case, the host clears **only** `ru.finassist.pf.stage.e2e`, then writes private `files/stage-run.json` containing `{run_id: UUID, phone: "+7000000xxxx"}`. Its helpers must not clear app data again. Clear/relaunch does not reset server state.
- Any account/upload deletion is scoped to the accounts created by this run. Never discover an existing account and erase it to make a fixture fit. Cleanup failure is reported independently from assertion failure.

## Host-to-flow inputs

| Environment input | Meaning |
|---|---|
| `STAGE_PHONE` | Ten digits without +7 of the receipt-owned existing account, or the fresh phone in `new-empty`. |
| `STAGE_PHONE_DISPLAY` | Regex-safe receipt phone, e.g. `\+7 000 000-12-34`; YAML interpolates the variable without escaping its dollar sign. |
| `STAGE_NEW_PHONE` | Ten digits of a fresh phone for registration/closed-registration cases. Never a shared fixture number. |
| `STAGE_NEW_PHONE_DISPLAY` | The corresponding formatted new phone, reserved for new-phone assertions. |
| `STAGE_FINANCIAL_QUESTION` | Exactly `#FIN362_TOOLS Покажи доходы и расходы по моим данным.` for the audited provider configuration. This is synthetic fixture input, not a model semantic-quality test. |
| `STAGE_UPLOAD_DATE` | Current upload date in the profile timezone, regex-safe Russian day/month fragment such as `30.октября`. The runner must not infer a date by pinning the client clock. |
| `STAGE_TODAY` | Today in the profile timezone in the same Russian day/month form; matches the fresh-baseline coverage end for Qase37. |

The static OFX parser fixtures retain their dates and line numbers. Qase23 asserts **7 October** rather than the mock-only word “yesterday”; it still verifies the fallback title and absence of description. Qase28 compares the real upload date supplied by the host. Analytics chooses September/October 2026 explicitly from the period sheet instead of assuming a previous-month step relative to a frozen clock. The selected period must exist in the real list; a missing period is a failure, not a reason to inject a fabricated response.

## Provider capabilities and provenance

The audited stage provider file from FIN-362 is `fin362-scenarios.json`, SHA256 `7d0b637f0c2d1f67293a1819fc93ce3d113c24ef468be9f44f449e3a578e7d5f`. This identifies the expected fixture; the host must bind the actual deployed revision in the environment report and detect drift. The local source and historical operator report alone do not establish the current server configuration.

- `#FIN362_TOOLS` invokes actual Overview/Aggregate/Find/ListPeriods peers and presents operation rows. Its text starts `FIN362_TOOLS_OK`.
- `#FIN362_ANALYTICS` obtains September 2026 analytics and presents block0; the real presenter produces the analytics navigation chip.
- `#FIN362_LIMIT` reads actual data coverage and then succeeds. It is suitable for exercising the real daily counter on an owned account.
- `#FIN362_OFF_TOPIC` invokes the real `off_topic` function. Five such terminal outcomes exercise the actual rolling-window limit; an injected 429 is not equivalent.
- An unmatched question takes the explicit provider refusal route. `ErrRefused` becomes `CANNOT_ANSWER`, no retry and no charge. Qase13 uses it before the five successful questions.
- `#FIN362_MODEL_ERROR` always fails. It cannot prove retry-success for the same persisted question. The provider has no fail-once control and no verified covered-period merchant-not-found scenario.
- Qase12 uses two real questions because the deployed fixture emits operation and analytics chips in separate scenarios. It verifies operation navigation after the first answer (5→4), then analytics navigation after a second answer (4→3), without adding a synthetic chip. Category navigation replaces the mock merchant-scope selector because the audited tools scenario groups by category.

A `client-injected` case still makes real stage calls outside its explicitly controlled fault/transform. Closed latency, HTTP/SSE refusals, truncation and unknown-body fixtures are local to the dedicated stageE2e build. They never change global stage maintenance/minimum version or provider configuration. `call_delay_ms` only withholds the client-visible verification result: the backend may already be VERIFIED. Qase41 remains blocked because this does not implement a real provider expiry.

## Complete source mapping

| Qase | Source root / native method | Candidate | Provenance | Assertion contract |
|---:|---|---|---|---|
| 1 | `auth/sign_in_existing_user.yaml` | ready | real-stage | Existing owned phone, baseline upload, real login and tabs; match the run phone. |
| 2 | `auth/register_new_user.yaml` | ready | real-stage | Unregistered owned phone, real registration and first upload. |
| 3 | `auth/phone_validation.yaml` | ready | client-injected | Client-only verified-event delay preserves the Call UI for edit-number assertions; backend may already be VERIFIED. |
| 4 | `auth/registration_closed.yaml` | ready | client-injected | Local registration flag, separate new and precreated owned phones. |
| 5 | `applock/unlock.yaml` | ready | real-stage | Real login; incorrect and correct local passcode after cold start. |
| 6 | `applock/forgot_code.yaml` | ready | real-stage | Real login; cancel and confirm forgotten-code logout. |
| 7 | `applock/change_passcode.yaml` | ready | real-stage | Real login; mismatched repeat and changed passcode after restart. |
| 8 | `operations/feed_and_detail.yaml` | ready | real-stage | Real operation category update remains visible after reopening. |
| 9 | `operations/search_filters.yaml` | ready | real-stage | Real search over the imported Krabsbir fixture, empty result, amount/kind/category/period filters. |
| 10 | `analytics/overview.yaml` | ready | real-stage | Explicit fixture months; real analytics blocks, period navigation, transfer mode and category link. |
| 11 | `analytics/blocks_switched_off.yaml` | ready | client-injected | Local flags hide blocks, transfers and assistant while other real blocks remain. |
| 12 | `assistant/ask_question.yaml` | ready | real-stage | Real consent, tool-backed operation rows and category navigation, then a second real analytics answer and analytics navigation; quota 5 to 4 to 3. |
| 13 | `assistant/daily_limit.yaml` | ready | real-stage | CANNOT_ANSWER then five charged outcomes need stage provider capability. |
| 14 | `statements/upload_and_history.yaml` | ready | real-stage | Real upload, exact-byte duplicate import, three history records, delete newest only. |
| 15 | `statements/upload_errors.yaml` | ready | real-stage | Real parser rejects CSV, non-statement and unsupported bank; cancel picker. |
| 16 | `StatementPickerTest#uploadsTheDocumentReturnedBySystemPicker` | ready | real-stage | Native ActivityResult path sends bundled OFX through the real stage importer; does not prove DocumentsUI interaction. |
| 17 | `profile/theme_and_logout.yaml` | ready | real-stage | Real profile theme PATCH; cancel and confirm logout. |
| 18 | `profile/delete_account.yaml` | ready | client-injected | Only owned account is deleted; client latency exposes busy state and prevents Back dismissal. |
| 19 | `offline/no_network.yaml` | ready | client-injected | Client transport failure on real analytics/profile; recovery rereads actual stage data. |
| 20 | `assistant/offline_retry.yaml` | ready | client-injected | Real first answer, client transport outage, real second charged answer and quota. |
| 21 | `statements/upload_offline_retry.yaml` | ready | client-injected | Client transport failure before upload; retry uses real stage import. |
| 22 | `statements/upload_unreadable.yaml` | ready | real-stage | Real unread parser line numbers and reasons; history opens unread list. |
| 23 | `statements/upload_no_description.yaml` | ready | real-stage | Real OFX operation without NAME displays fallback title; fixed 7 October header replaces frozen yesterday. |
| 24 | `assistant/chip_after_statements_deleted.yaml` | blocked | real-stage | Real answer scope survives deletion of all owned uploads; stale selection fallback and empty ledger. |
| 25 | `assistant/retry_without_transfers.yaml` | blocked | client-injected | Persisted model failure, retry and original transfer mode. |
| 26 | `assistant/answer_retry.yaml` | blocked | client-injected | Persisted model failure, double retry and one charged result. |
| 27 | `operations/days_in_profile_zone.yaml` | ready | real-stage | Real owned-profile PATCH/GET changes timezone; actual operation day grouping crosses midnight. No response/transport substitution. |
| 28 | `statements/upload_unreadable_fixed.yaml` | ready | real-stage | Real correction preserves account/FITID identity and resolves historical unread rows; upload date comes from runner. |
| 29 | `operations/unknown_filter_kind.yaml` | ready | client-injected | Closed client compatibility peer observes the intact unknown expense kind, then maps only that alias to the real expense query with every other scope/filter retained. Does not prove live-server support for a new kind. |
| 30 | `analytics/block_unavailable.yaml` | ready | client-injected | Client transforms one real analytics block to unavailable; sibling blocks remain real. |
| 31 | `analytics/unknown_blocks.yaml` | ready | client-injected | Client adds two closed unknown-code blocks to a real analytics payload. |
| 32 | `statements/upload_unknown_codes.yaml` | ready | client-injected | A valid real import complete frame gains only closed unknown notice/feature fields; accounting, IDs and nonterminal/error frames remain real. |
| 33 | `availability/update_required.yaml` | ready | client-injected | Client injects version-unsupported wire error; global stage minimum stays unchanged. |
| 34 | `availability/maintenance.yaml` | ready | client-injected | Client injects maintenance and Retry-After; captured deadline survives later controls; real availability GET on recovery. |
| 35 | `availability/maintenance_on_sign_in.yaml` | ready | client-injected | Client maintenance refusal during login, unknown end time. |
| 36 | `analytics/cards_open_search.yaml` | ready | real-stage | Real category/merchant/payment groups link to actual operations. |
| 37 | `operations/statement_stale.yaml` | ready | real-stage | Fresh baseline extends statement coverage to today without changing operations; then real stale file replacement. |
| 38 | `analytics/search_scope_own_transfers.yaml` | ready | real-stage | Real own-transfer 6000 is absent from scoped analytics search and present after scope removal. |
| 39 | `assistant/off_topic.yaml` | ready | real-stage | Real fake-LLM off_topic terminal outcome refunds quota, then real tool-backed answer. |
| 40 | `assistant/answer_changed_since.yaml` | ready | real-stage | Real history changed_since tracks category change and upload deletion. |
| 41 | `auth/call_expired.yaml` | blocked | client-injected | Backend expiry rather than client-side event delay. |
| 42 | `assistant/nothing_found_charged.yaml` | blocked | real-stage | Tool-backed no-results in covered period is charged. |
| 43 | `offline/stream_drops.yaml` | ready | client-injected | Client truncates three actual stage SSE streams; reopening returns real terminal outcomes. |
| 44 | `auth/call_stream_rate_limited.yaml` | ready | client-injected | Client delay/drop/429 of actual phone SSE; exact opening count and captured Retry-After. |
| 45 | `statements/upload_rate_limited_resume.yaml` | ready | client-injected | Real import; client stream 429 deadline survives Home/resume before real terminal result. |
| 46 | `availability/maintenance_during_import.yaml` | ready | client-injected | Server import continues while client stream sees maintenance; real history has completed upload. |
| 47 | `statements/upload_history_period.yaml` | ready | real-stage | Actual parser derives explicit calendar range, transaction fallback date and zero-operation history. |
| 48 | `errors/server_messages.yaml` | ready | client-injected | Closed malformed/unknown REST fixtures at profile PATCH exercise safe presentation. |
| 49 | `assistant/off_topic_limit.yaml` | ready | real-stage | Five real fake-LLM off_topic outcomes enforce actual rolling-window block; draft and daily quota remain. |
| 50 | `errors/request_retry_false.yaml` | blocked | client-injected | Retry prohibition counter plus persisted model-failure recovery. |
| 51 | `statements/upload_restored_key.yaml` | ready | client-injected | Closed local unknown-age upload-key seed; real GET count proves no automatic upload and exactly one explicit new intent. |
| 52 | `assistant/retry_after_consent.yaml` | blocked | client-injected | Persisted failed question, consent correction and exactly one history question after retry. |
| 56 | `statements/unread_same_physical_line.yaml` | ready | real-stage | Actual parser emits two identical unread occurrences on physical line 31. |
| 57 | `auth/phone_confirmation_gates.yaml` | ready | client-injected | Independent unchecked confirmations and returning from Call preserve valid choices; repeated helper is idempotent. |
| 58 | `auth/phone_consent_recovery.yaml` | ready | client-injected | Client network failures at public document GET and phone Start; explicit recovery uses real stage requests. |
| 59 | `auth/phone_restart.yaml` | ready | client-injected | Closed terminal Start refusal; explicit restart clears confirmations without another POST, then real login. |

## Blockers that require a real independent server state

| Qase | Required mechanism |
|---:|---|
| 24 | The deployed tools scenario returns category/all-time filters, not a verified expiring merchant selection. Removing uploads alone cannot prove the original stale-selection fallback. |
| 25 | RetryAnswer must recover a persisted MODEL_ERROR using the original question and transfer mode; no run-scoped fail-once provider is available. |
| 26 | A real persisted failed answer must succeed on retry; a client-only SSE substitution cannot create that server state. |
| 41 | A real pending verification must expire and accept an explicit new Start; client latency does not change fake-phone server state or TTL. |
| 42 | The stage fake-LLM has no verified merchant-not-found tool scenario for a covered period; a synthetic answer cannot prove server charging. |
| 50 | The REST retryable=false portion is injectable, but the full original case also requires persisted MODEL_ERROR followed by a successful RetryAnswer. |
| 52 | The original failed question must persist across consent revoke, history read, regrant and successful RetryAnswer; no run-scoped fail-once provider exists. |

The following safe mechanisms would unblock the relevant cases: a run-scoped fake-provider scenario version keyed by a synthetic question/request context which persists an initial failure and succeeds for an explicit retry of that same question; a run-owned phone-provider outcome that remains pending and genuinely expires; a merchant tool query/selection for a covered range with no matches; bounded import-SSE response transforms for unknown codes. These are dependencies to implement and verify, not permission to add public stage/prod backdoors or to alter global provider behavior during a run.

## Additional process-death acceptance

The original 56 cases do **not** prove death during an uncertain in-flight assistant request. App-lock cold launches occur after completed authentication; Qase51 seeds an unknown-age upload key and does not kill the process. Additional Qase60 / `process-death-assistant` uses a host checkpoint: actual Ask accepted, response still held and not released, Home, `am kill`, confirmed PID disappearance, relaunch/unlock and actual history. It checks one accepted POST, one persisted question and remaining quota4. `ready` is authored readiness, not a claim of a successful fresh run. A client-synthetic failed answer is not acceptable evidence.

A process-death test must record real pre/post request counts and the same server-side question identity without printing its tokens or private bodies. Its recovery should run against the independent stage backend that outlives the client.

## Native coverage boundary

`StageStatementPickerTest#uploadsTheDocumentReturnedBySystemPicker` is the stage mapping of Qase16. It uses the native ActivityResult/ContentResolver boundary and verifies the real import terminal result. Like the source native test, it does not drive Android DocumentsUI: that system intent belongs to the existing picker contract unit test, and a claim of a fully exercised system-picker UI would be incorrect.

## Authored baseline

This inventory currently has 49 ready source candidates and 7 blocked source cases, plus the authored Qase60 process-death candidate. No readiness flag claims a local/device pass. The report records fresh environment, selected APK/source SHA, provider fixture expectation, account ownership evidence, assertion outcomes, client fault counters and sanitized failure materials separately. Backend/provider missing mechanisms are tracked by FIN-387.

### Closed client compatibility fixtures (Qase29/32)

Qase29 does not claim that the current stage server understands `expense_e2e`. The real expense tile gains that closed
unknown code, which the UI must preserve through its chip and query serializer. The test interceptor counts the original
unknown query and translates only this one alias to the server's `expense`, retaining origin and every other filter.
The row's `required_counts.unknown_kind_requests` must be met before it can pass. Other unknown codes and duplicate kind
parameters are never translated. This separates client open-code compatibility from current backend enforcement.

Qase32 wraps only a successful real import progress stream. It buffers at most one64KiB input frame, preserves all other
events and framing, validates the original `ImportResult`, then appends the two closed unknown-code fields to `complete`.
The actual import, amounts, counters, accounts, currency, coverage and IDs are not fabricated. Malformed/error responses
never become success; oversize frames fail safely. `required_counts.import_response_transform` proves the transformed
terminal payload was consumed rather than merely configuring the fixture. Neither fixture establishes a provider fault.

## Required observations for client controls

Every ready `client-injected` row declares positive `required_counts` for each effect it needs.
The runner and aggregate report reject a green assertion result when the transport/local-control
observation is missing; configuring a control, recording unrelated traffic or merely setting
`has_client_injections` cannot satisfy it. The inventory contract also rejects a missing or weakened
observation declaration. Qase43 requires six actual stream drops (two in each of phone, import and
assistant), Qase48 four injected PATCH refusals, and Qase11 four applied flag overrides.

Local flag overrides/reset and the unknown-age upload-key seed record their own observations only
after the DataStore/key operation succeeds. Fault cleanup does not generate these observations.
Qase27 is `real-stage`: the control performs the actual scoped profile PATCH, followed by actual GET
and UI grouping assertions, rather than a fake timezone response.

Qase3 uses `new-empty` with `registration_expected:false`: its first Start only reaches Call and returns to phone
editing. It never registers, reads an account or needs a baseline ledger. The ordinary `new-empty` registration
case still requires a correlated successful Register receipt; this exception does not relax that ownership check.
