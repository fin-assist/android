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

Before a refresh RPC, `EncryptedTokenStore` durably reserves its token/key pair in a separately
encrypted pending marker. Successful token replacement retires it atomically. A new authenticator
cannot guess the original key's age after process death: an uncertain marker requires sign-in
recovery instead of another refresh with a new key. The old session blob format is unchanged.
Compare-and-set updates/clears and a masked request-session tag prevent a late response from
replaying an old user's operation or removing a newer login. Failed refreshes respect their own
request retry restriction and wait, independently of the original operation.

A validated first refusal before key/body admission (REQUEST 429 RATE_LIMITED, 503 MAINTENANCE or
400 CLIENT_VERSION_UNSUPPORTED) can retire only its matching pending marker by compare-and-set.
The same UUID and its original origin remain unchanged; the refresh's first possible-use window
has not started. The wait and explicit `false` still apply. Any earlier uncertain outcome remains
sticky: a later pre-admission refusal cannot prove that an older request did not use the token or
renew its 60-second window. Unknown/restored markers remain conservative. Update requires an actual
update; maintenance has only the documented guarded correction. A first explicit false refusal keeps
the session without renewing the token window. If a later 401 reaches that permanently forbidden
refresh with no remaining maintenance correction, compare-and-set clearing returns to sign-in.
A temporary wait, an unused maintenance correction and an update requirement retain the session;
a newer login cannot be cleared by an old request.

Upload action identity includes the content digest and the raw filename (FIN-340). A pending legacy
content-only record cannot become a fresh named action during an upgrade: it enters unknown-outcome
recovery without creating an alias UUID. Only explicit new intent retires that legacy action. Manual
retry preserves the picked name; changed file content requires recovery before another upload.

An unknown-age upload or assistant request has a separate visible recovery state. It preserves the
old key and explains the unknown outcome; checking history does not send a new mutation. Only an
explicit new-intent action retires that pending action and creates a new tracked key. A new
RetryAnswer additionally needs permission from the current failed result, checked through fresh history.

After `CONSENT_OUTDATED` in phone Start or assistant consent, the rejected version remains forbidden until a
successful document GET returns a different version. The new document is installed and its acceptance
is reset before releasing the old mutation restriction. A failed, same-version or stale GET cannot
permit the old POST/PUT. Both screens expose «Обновить документ»: it performs only the independently
permitted document GET, with its own captured wait/false restriction and one pending read at a time.
This recovery does not create a key, renew its age, submit a mutation or renew the registration token.
FIN-365: Register sends the original Start consent/version without another checkbox or document GET. Its version is
pinned even if current_version rotates during the call. A Register evidence mismatch or a missing local original
request starts sign-in over; it never replaces the receipt with a newly fetched version.

For phone Start only, a received 429 keeps the captured server wait but selects a NEW key on the next explicit
«Запросить заново» intent. This does not replay a terminal dispatched request whose retryable is false. A lost
transport response retains the original body/key. `PhoneStartGate` keeps this new-intent wait apart from ordinary
request retry permission; it never starts a POST automatically and never invents a fallback five-minute timer.

## Time

Calendar dates and display still use the existing `Clock.zone` rules. Waiting uses `ElapsedClock`,
bound to Android `SystemClock.elapsedRealtime()` in the app, including device sleep. A fresh
`Retry-After` duration or `expires_in` takes priority. An absolute target may be compared with the
fresh `Date` of the same response, then reduced by monotonic elapsed time; it is an approximate UI
deadline, never proof of token validity, idempotency age or permission.

`ResponseTiming` and `ServerDeadline` are process-local. Phone verification producers recompute
fresh timing on replay instead of caching receipt anchors. Without an anchor, refresh state or omit
an exact countdown; never subtract the device wall clock or restart a saved duration.
The per-stream `SseReopenState` also retains a pending 429 wait across collector cancellation;
returning to a screen cannot skip the wait or start its duration again.
Features explicitly opt into the known maintenance correction through
`ServiceGate.recoveryDeadline` and the generic `reopeningStream(recoverOnceAfter = …)` callback.
It permits one opening after the captured wait; a second refusal before any successful event stays
blocked. Cancellation cannot renew the permit or deadline. UpdateRequired gets no automatic permit.

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

## Background time-zone synchronization (FIN-345)

`TimeZoneChecker` keeps independent durable restrictions for GET profile (login context) and PATCH
profile (login plus the exact target time zone). Foreground, sign-in and system time-zone triggers
share the same state. Repeating a trigger or observing the same profile does not clear a refusal.
Only an actual new login resets GET; an actual target change resets PATCH without resetting GET.
The existing silent UX, profile-zone cache and domain 72-hour threshold are unchanged. These are
Android consumers of the existing profile endpoints; this change adds no bank-bff route.

The encrypted token store assigns an opaque `authSessionEpoch` atomically with a new login, including
a new login by the same user. Refresh retains it. Legacy encrypted sessions acquire an epoch once,
without rewriting their ciphertext. DataStore state uses a versioned record, owner and revision CAS;
GET and PATCH each write `IN_FLIGHT` **before** dispatch. A process interrupted before the outcome is
stored leaves uncertainty blocked until a valid new context. Failed storage never grants a request.
A received ordinary offline error permits a later legacy retry; explicit false remains forbidden.
No tokens, diagnostics or server message catalog are persisted in these records.

A wait captures a separate typed `{bootCount, capturedElapsedMillis, deadlineElapsedMillis}` once.
[`elapsedRealtime`](https://developer.android.com/reference/android/os/SystemClock#elapsedRealtime())
includes device sleep; [`BOOT_COUNT`](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT)
proves that a restored elapsed deadline belongs to this boot. A same-boot process restart continues
the remaining wait without a background timer. Reboot, unknown boot identity, elapsed regression or
a missing valid response anchor fail closed; they do not restart the old relative duration and do
not consult the phone wall clock. False remains false after the wait expires. This durable consumer
snapshot does not change the process-local meaning of `ResponseTiming` or `ServerDeadline`.

Background requests carry a local coroutine owner converted to a Retrofit request tag, never a wire
header. Token selection and authentication recovery check both user and epoch. Refresh token writes,
refresh markers and clearing are epoch-bound DataStore CAS operations; a late refresh cannot replace
a newer login. Mock profile calls enforce the same binding. External profile observations carry their
captured owner/revision, so an older response cannot undo a later background mutation or login.
Ordinary unit tests cover persistence, restart/reboot, independent restrictions, stale callbacks,
write-ahead cancellation, token CAS and actual HTTP dispatch. No UI, instrumentation, Maestro or
design-check execution is needed for this silent logic.
