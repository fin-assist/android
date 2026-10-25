# Errors, retries and server time (FIN-223)

The source contract is `fin-assist/contracts`, `docs/api.md` section 8 and `openapi/openapi.yaml`.
The mock contract fixtures are pinned to merged FIN-224 commit
`f153dc43858d0fc5073d48dc39d77377828add73` in both `VERSION` files.

## Presentation

`core/common` owns `ErrorPresentation`, `UserMessage` and `ErrorSeverity`. `core/api` decodes the
optional `user_message` / `severity` fields and the corresponding history fields; `core/network`
retains them in every specialized `AppError.response` alongside the raw code, status and corrective
metadata. Unknown codes remain available as raw strings. Unknown severity uses `ERROR`.

`UserMessage` preserves three states: absent, safe and present-invalid. Validation counts Unicode
code points (1–1000), rejects broken surrogate pairs and requires a character outside the contract's
Unicode White_Space set. A malformed canonical value cannot resurrect a legacy message. An absent
value may use a validated legacy user-facing text; the final fallback is a controlled feature string.
Diagnostic `message` and exception text are never a presentation fallback.

Feature view models retain the presentation in state. `ErrorMessage` draws plain text with its
severity; text, code and severity do not create links or actions. Existing actions belong to the
feature's endpoint/scenario adapter. `ErrorContext.REQUEST`, `GENERIC_EVENT` and `RESULT_EVENT`
prevent an identically named result code from activating a REST auth/maintenance/quota flow.
Assistant terminal results and history preserve their separate new-attempt permission.

## Repeating a request

REST `retryable` describes the original request only. Explicit `false` prevents direct retries and
automatic stream reopening in the same context. `RequestRetryGate` restricts one existing callback
per operation and captures its wait once; it never selects an action or proves a POST safe.
Features separately preserve input/key and check the actual replay window at send time. A result's
new-generation retry remains a different operation, with its own key and REST restriction.

Fresh `IdempotencyKey.random(elapsedClock)` carries an ephemeral replay window. String construction
and deserialization preserve the UUID with unknown age. `IdempotencyKeys` persists a pending action
until explicit completion; expiry never silently replaces its key. Only a key created by the same
store instance retains its original in-process proof. Refresh additionally has its own 60-second
replay window. Corrective auth recovery and the existing single maintenance
resume still require the original request's safety and a valid key window.

## Time

Calendar dates and display still use the existing `Clock.zone` rules. Waiting uses `ElapsedClock`,
bound to Android `SystemClock.elapsedRealtime()` in the app, including device sleep. A fresh
`Retry-After` duration or `expires_in` takes priority. An absolute target may be compared with the
fresh `Date` of the same response, then reduced by monotonic elapsed time; it is an approximate UI
deadline, never proof of token validity, idempotency age or permission.

`ResponseTiming` and `ServerDeadline` are process-local. Phone verification producers recompute
fresh timing on replay instead of caching receipt anchors. Without an anchor, refresh state or omit
an exact countdown; never subtract the device wall clock or restart a saved duration.

## Mock and verification

`MockErrorCatalog` supplies safe server text. Closed `MockErrorScenario` controls exercise unknown
codes, unknown severity, invalid text, legacy responses and retry refusal; their control-link hooks
exist only in `mockE2e`. They do not add a client code-to-message catalog.

`AssistantOffTopicPolicy` enforces a rolling seven-day window at admission: replay happens first,
terminal off-topic outcomes count once, and blocking a new request does not consume the daily
quota. A charged successful completion resets the rolling counter; failed or uncharged outcomes do
not. Existing streams, history and quota reads remain available.

Unit tests cover decoding, context isolation, timers, replay safety, mock policy and feature state.
Design-check fixtures and Maestro flows are authored separately; their execution is subject to the
owner's UI-test permission. Qase mappings and flow coverage live in [e2e.md](e2e.md).
