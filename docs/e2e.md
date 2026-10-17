# UI tests (e2e)

Two layers, both on an emulator or device:

| Layer | What | Where | Build |
|---|---|---|---|
| Maestro | User scenarios end to end: sign-in, passcode, operations, search, analytics, assistant, statements, profile, flags, offline | `.maestro/` | `mockE2e` |
| Native (Espresso / Compose test) | What Maestro cannot drive reliably — the system document picker | `app/src/androidTestMock/` | `mockE2e` (`connectedMockE2eAndroidTest`) |

Native tests are for the gaps, not a second copy of the scenarios. When a check needs the platform itself
(system dialogs, intents, permissions, biometrics), it goes there; everything a user does on our screens goes to
Maestro. The same split will apply to iOS (XCUITest for the gaps).

## Running

Prerequisites: an emulator or device with `adb`, [Maestro](https://maestro.dev) on PATH
(`curl -Ls https://get.maestro.mobile.dev | bash`), JDK 21. Set the emulator time zone to Europe/Moscow
(Settings → System → Date & time, or `-timezone Europe/Moscow` when starting it).

```bash
# All flows and the native tests
scripts/e2e.sh

# Quick pass: flows tagged smoke, no native tests
scripts/e2e.sh --tags smoke

# One flow while writing it (Maestro Studio helps to find elements: `maestro studio`)
scripts/e2e.sh --no-build --flow .maestro/flows/assistant/ask_question.yaml
```

Reports go to `build/e2e/`: JUnit XML, `maestro-output.tgz` (per flow: screenshot and view hierarchy of the failed
step, commands, log), logcat, the preflight dump, and in `coverage/ec/` the JaCoCo execution data the flows left on
the device (`./gradlew :e2eCoverageReport` turns it into the UI test coverage report — docs/testing.md, «UI test
coverage»).

In CI (`.github/workflows/e2e.yml`) the tests run after a merge to `main`; PRs never run them automatically. A
failed run is re-run from the Actions tab («Re-run jobs»); any branch can be run by hand there («Run workflow» →
the branch) to check a new flow or the coverage pipeline before the merge — such a run sends nothing to Qase. The
run summary lists the flows and the UI test coverage; reports are in the `e2e-reports` artifact.

## Qase

Every scenario is a test case in Qase, project `PLAINFIN`, suite «UI-автотесты (Android)» (one sub-suite per feature):
steps and expected results in Russian, marked automated.

- `scripts/qase-cases.json` maps tests to cases: a Maestro flow by its path (`.maestro/flows/<feature>/<flow>.yaml`),
  an instrumented test by `<class>#<method>`.
- After a merge to `main` the `UI tests` workflow sends the results as one Qase run
  (`scripts/qase-report.py`: JUnit reports → run → bulk results → complete). The run title is
  `UI tests · <branch> · <sha>`, its description links the Actions run. Cancelled runs are not sent.
- Statuses: passed, failed (an assertion `failure` and an unexpected `error` alike: in UI tests the latter is usually
  an app crash), skipped. A test found in several reports keeps its worst result.
- If the results cannot be recorded, the script retries once (429, 5xx, network) and otherwise deletes the empty run.
- The token is the repository secret `QASE_API_TOKEN`. Without it the step prints a notice and passes; a Qase error
  does not fail the job either.
- Check what would be sent without a token: `scripts/qase-report.py --dry-run build/e2e/maestro-report.xml`.

## The `e2e` build type

`assembleMockE2e` builds `ru.finassist.pf.mock.e2e`: the mock flavor plus test hooks from `app/src/mockE2e/`. It
installs next to `mockDebug` and never exists for `prod`. It is also the `testBuildType`: the native tests run on
it, and only it carries the JaCoCo probes (docs/testing.md, «UI test coverage»).

- **Pinned date.** "Now" starts at 2026-10-08 12:00 MSK (`BuildConfig.E2E_NOW`, `ShiftedClock`) and runs from
  there — for the mock backend and the screens alike (`LocalPfClock`), whatever the emulator's date. The fixture statement covers October 2025 — September 2026, so periods, amounts and limits stay the same
  whenever the tests run.
- **Fixture only.** The fake backend always loads `statements/fixture.ofx`, never a private statement on the
  machine (`E2eModule`). Delays are short: the call «arrives» in 1.5 s, imports and answers stream fast.
- **Registration open.** `assets/flags.json` is empty, so flags have their code defaults; the existing account is
  `+7 916 123-45-67`, any other number registers.
- **File picker.** The upload screen opens a list of bundled files instead of the system picker
  (`E2eDocumentPickerActivity`, see `PickStatementDocument`). Files in `app/src/mockE2e/assets/e2e/statements/`:

  | File | Result |
  |---|---|
  | `fixture.ofx` | the preloaded statement again: nothing new |
  | `october.ofx` | 3 new operations, 3–6 October 2026 |
  | `statement.csv` | `CSV_NOT_ACCEPTED` |
  | `notes.txt` | `WRONG_FORMAT` |
| `unreadable.ofx` | no readable operation, 2 unread lines (`EMPTY_STATEMENT`, `unread_count = 2`) |
  | `unreadable-fixed.ofx` | the two operations of `unreadable.ofx` read correctly (same account and FITID): closes its unread lines (FIN-79) |
  | `no-description.ofx` | 1 new operation without a description (7 October 2026): «Без описания» in the feed, no unread line |
  | `stale.ofx` | 3 operations, statement period 1 August — 20 September 2026, the last operation on 15 September: once the preloaded statement is deleted, the data is stale on the pinned date — the banner says «по 20 сентября» (FIN-125) |
  | `other-bank.ofx` | `WRONG_BANK` |

- **Control link.** `pfe2e://config?…` changes state from a flow (`E2eControlActivity`). Open it through the subflow,
  not a bare `openLink`: `runFlow: {file: ../../subflows/config.yaml, env: {LINK: "pfe2e://config?…"}}`. The activity
  writes the values, shows `e2e.config.applied` and finishes; the subflow waits for that, so a `launchApp` right
  after cannot cut the writes short.

  | Parameter | Effect | Lifetime |
  |---|---|---|
  | `flag.<key>=true\|false\|default` | feature flag override, keys from `docs/flags.md` | app data (wiped by `clearState`) |
  | `flags=reset` | drops all overrides | — |
  | `offline=true\|false` | every backend request fails with «нет сети» | process |
  | `call_delay_ms=<n>` | how long the sign-in call takes | process |
  | `network_delay_ms=<n>` | latency of every backend request (a «busy» state stays up for that long) | process |
  | `unknown_codes=true\|false` | the fake server sends codes the client does not know (`MockCodes`): a filter `kind` on the expense tile, an import notice, an analytics feature, an Analytics block of an unknown type and a card locked for an unknown reason (FIN-83, FIN-4) | process |
  | `failing_blocks=<id>,…` | those Analytics blocks fail on the fake server and come as `unavailable` (FIN-5); empty — none | process |
  | `assistant_model_errors=<n>` | the next n assistant answers fail with MODEL_ERROR («Повторить» under them) | process (counts down) |
  | `profile_timezone=<zone id>` | `profile.timezone` on the fake server, as if changed elsewhere; the app adopts it on the next profile read (opening «Профиль») | process |

  Flags are read when a screen opens: set them before the screen (usually right after `clear_state.yaml`, before
  `launchApp`). Process-scoped values are set after `launchApp`.
- **Coverage data.** `E2eCoverage` (a content provider) saves the JaCoCo execution data of the process every 2 s
  while it changes and when an activity stops, into shared storage (`Download/pf-coverage`), where `clearState`
  does not reach; `scripts/e2e.sh` pulls it after the flows. Native tests go through the test storage service
  instead (`useTestStorageService`). The system picker contract that the native test used to check on `mockDebug`
  is now a Robolectric test (`PickStatementDocumentTest`): on the e2e build the picker is `E2eDocumentPickerActivity`.

## Test tags

Maestro finds elements by id; in Compose the id is `Modifier.testTag`, exposed as a resource id by
`Modifier.pfTestRoot()` (set on the app root, dialogs and bottom sheets) — only in debuggable builds (`pfExposeTestTags`,
set in `PfApplication`); release builds keep tags out of the accessibility tree.

- Screens tag their own elements as `<feature>.<screen>[.<element>]`: `auth.phone.input`, `operations.feed`,
  `statements.upload.error.wrong_bank`. The constants live next to the screens (`AuthTags`, `OperationsTags`, …).
- Shared components use `ds.*` (`PfTestTags`): `ds.back`, `ds.dialog.confirm`, `ds.numpad.<digit>`,
  `ds.tab.<operations|analytics|profile>`, `ds.chat.input`.
- Analytics blocks are tagged with the names of the flags that hide them (`analytics.block.tiles`, …).
- Tag the screen root, every control a scenario uses, and states a scenario checks (errors, empty, offline).
  Prefer ids to texts: texts are fine for checking content, not for finding controls.
- Renaming a tag breaks flows: search `.maestro/` first.

## When a change needs a UI test

A PR that adds or changes something the user does or sees covers it in the same PR (rule in `AGENTS.md`, Code):

| Change | UI test |
|---|---|
| New screen, sheet or dialog | a flow that reaches it and checks what it is for (not only that it opens) |
| New state of a screen: error, empty, offline, limit | a flow that brings the app into it (fixture file, control link, flag) |
| New step or branch between screens | the feature's flow walks the new path; a separate flow when it has its own goal or start state (another account, flag, fixture) |
| Changed behaviour without new UI (validation, limits, what a button does) | the existing flow's checks follow the new behaviour |
| Flag that changes behaviour | a flow with the flag switched (`config.yaml`), next to the default path |
| User-visible bug fix | a check that fails without the fix: the flow reproduces the bug |
| Copy, colours, layout only | none — the design check covers it |
| Logic without UI (parsing, accounting, API) | unit tests, not UI tests |
| Refactoring that keeps behaviour | none; the existing flows must stay green |
| Renamed test tag | the flows that use it (search `.maestro/`) |

Maestro is the default. A native test (`app/src/androidTestMock`) covers what Maestro cannot drive — system
pickers, intents, permissions; keep it to that gap. When a scenario cannot run on the emulator (hardware it lacks,
a bug that does not reproduce there), list it in «Not covered yet» at the end of «Coverage» with the technical
reason and say so in the PR; «takes long to write» is not a reason.

Every new or renamed test: a Qase case (suite of the feature, steps and expected results in Russian, automated),
a line in `scripts/qase-cases.json`, a row in «Coverage». CI runs the flows after the merge to `main`; before that
they run locally (`scripts/e2e.sh`) or by a manual start of the `UI tests` workflow, and the PR says whether they
were.

## Writing a flow

- One file per scenario under `.maestro/flows/<feature>/`, `name` in Russian, tags: the feature and `smoke` for the
  short set (one happy path per feature).
- Start from a known state: `runFlow: ../../subflows/clear_state.yaml`, then `runFlow: ../../subflows/login.yaml`
  (existing account, passcode 1234, ends on the feed) or `launchApp` for sign-in flows. Not a bare `clearState`:
  right after `pm clear` Android may still kill a freshly started app process while it removes the old task, and
  the flow waits on a blank window. `clear_state.yaml` pauses for 3 s after the wipe; `sign_in.yaml` relaunches
  the app once if the phone screen still does not show up.
- Shared steps are in `.maestro/subflows/`: `clear_state`, `config` (env `LINK`), `login`, `sign_in` (env `PHONE`), `set_passcode`,
  `enter_code` (env `CODE`), `relaunch_and_unlock` (env `CODE`).
- Wait for screens with `extendedWaitUntil` rather than fixed sleeps; scroll to below-the-fold elements with
  `scrollUntilVisible` before tapping them.
- Type ASCII where you can (merchant `Krabsbir`, digits): non-ASCII input depends on the emulator keyboard.
- `id` and `text` in one selector must match the same node. A tag on a container (`PfLink`, `PfPageHeader`, a
  row) has no text of its own — its text is a child node: check the id and the text in two steps.
- Labels that depend on «today» («Сегодня», «Вчера», the year next to a date) follow the pinned date, not the
  emulator's: 7 October 2026 is «Вчера».
- A new flow needs a case in Qase and a line in `scripts/qase-cases.json`; a renamed or removed flow updates both.
  `scripts/test_qase_report.py` (CI, `agent-docs` job) fails on a flow without a case or a case without a flow.

## Coverage

| Flow | Checks |
|---|---|
| `auth/register_new_user` (smoke) | new number → consent (error without the tick) → passcode → first upload → result → feed |
| `auth/sign_in_existing_user` (smoke) | existing account → passcode → feed; the three tabs |
| `auth/phone_validation` | short number error; call screen; «Изменить номер» keeps the number |
| `auth/registration_closed` | `auth.registration=false`: new number → «Регистрация пока закрыта»; existing account still signs in |
| `applock/unlock` (smoke) | cold start → unlock; wrong code error |
| `applock/forgot_code` | «Забыли код?» → cancel / sign out |
| `applock/change_passcode` | current → new → mismatch → new → repeat; the new code unlocks after restart |
| `operations/feed_and_detail` (smoke) | feed → card → change category → saved as manual |
| `operations/unknown_filter_kind` | `unknown_codes=true`: the expense tile sends a `kind` the client does not know; the search keeps it under «Как на „Аналитике“» and finds no incomes — FIN-83 |
| `operations/days_in_profile_zone` | `profile_timezone=Asia/Vladivostok`: after the profile is read, the feed groups days in the profile zone, not the device's — an evening operation in Moscow moves to the next day — FIN-92 |
| `operations/statement_stale` | the preloaded statement deleted, `stale.ofx` uploaded: «Операции» and «Аналитика» show «Данные в приложении по 20 сентября — с тех пор прошло больше двух недель», dated by the statement's end (`state.data_to`), not the last operation (15 September) — FIN-125 |
| `operations/search_filters` | empty result → «Сбросить фильтры» with the query alone clears it; query, «Только расходы», amount, «Сбросить», period, result card; «Без категории» in the picker's «Системные» — FIN-108 |
| `analytics/overview` (smoke) | blocks, previous month, month/quarter/year, «Без переводов», category → operations |
| `analytics/block_unavailable` | `failing_blocks=expense_categories`: the block shows «Не получилось посчитать», the other blocks stay — FIN-5 |
| `analytics/unknown_blocks` | `unknown_codes=true`: a block of an unknown type is shown by its text, a card locked for an unknown reason by the server's lock text — FIN-4, FIN-83 |
| `analytics/cards_open_search` | September: «Мелкие частые траты» opens its first group as a selection (chip with the group's name), a row of the card — its own group, a row of «Подписки и регулярные платежи» — that payment's charges — FIN-108 |
| `analytics/blocks_switched_off` | flags hide tiles, categories, transfers chip, assistant (card and profile) |
| `assistant/ask_question` (smoke) | consent (error without the tick) → question → answer → limit 4 of 5 → chip → scoped search |
| `assistant/daily_limit` | unanswerable question is free; five questions exhaust the day |
| `assistant/offline_retry` | no network: the notice replaces the input; «Повторить» sends the question once the network is back |
| `assistant/chip_after_statements_deleted` | every statement deleted: the operations chip of an earlier answer opens the search without its stale selection (notice, empty result) — FIN-76 |
| `assistant/retry_without_transfers` | `assistant_model_errors=1`: a question asked «Без переводов» fails, not spent; «Повторить» answers it still «без переводов» — FIN-76 |
| `assistant/answer_retry` | `assistant_model_errors=1`: the answer fails, not spent; a double tap on «Повторить» while the request is on its way sends it once: one answer, no «Не получилось отправить вопрос», 4 of 5 — FIN-75 |
| `statements/upload_errors` | CSV, not a statement, another bank; leaving the picker |
| `statements/upload_unreadable` | a file with no readable operation: the empty result still shows the unread lines notice; the history row opens «Непрочитанные строки» (line, date, reason) |
| `statements/upload_unreadable_fixed` | `unreadable.ofx`, then `unreadable-fixed.ofx`: the old upload's row says «Строки дочитаны в загрузке от 8 октября» and has no «Не прочитали» link — FIN-79 |
| `statements/upload_and_history` (smoke) | new operations; the same file again — nothing new → «История загрузок»; delete an upload |
| `statements/upload_offline_retry` | «Не скачивается?» sheet; no network while sending: notice on the guide, «Повторить» sends the same file |
| `statements/upload_no_description` | a line without a description is imported (no unread notice) and shown as «Без описания» in the feed and on the operation |
| `statements/upload_unknown_codes` | `unknown_codes=true`: the import result shows the server's text for a notice code and for a locked analytics feature the client does not know — FIN-83 |
| `profile/theme_and_logout` | dark theme; logout cancel / confirm with the notice |
| `profile/delete_account` | passcode (wrong, right) → «Удалить аккаунт?» over the profile → «Удаляем…» ignores Back → «Аккаунт удалён» |
| `offline/no_network` | «Нет сети» on analytics and profile, «Повторить» after the network is back |
| native `StatementPickerTest` | upload with a picker result stubbed by Espresso-Intents (on the e2e build the picker is `E2eDocumentPickerActivity`) up to the import result; the `ACTION_OPEN_DOCUMENT` intent itself: Robolectric `PickStatementDocumentTest` |

Not covered yet: voice input (needs a speech recognizer on the emulator), biometrics (needs an enrolled
fingerprint), custom date ranges in search,
links inside a sentence — the registration terms and privacy policy, «Как мы защищаем данные», «разобрать» and
«Подробнее» on the import result, «Подробнее» on «Аналитика» (a link inside a text node has no own view or
resource id for Maestro to tap, and the first three open an external browser the flow cannot check).
The server's own check of the phone number (400 `VALIDATION_ERROR` at `phone`, FIN-68) cannot be reached either:
the app sends only `+7` and 10 digits, the very rule the server checks, so neither a real build nor the mock gets
that error; `PhoneViewModelTest` covers the branch, `auth/phone_validation` the app's own check of a short number.
An answer stream ending in an error other than the network (404 for an answer the server no longer has) while the
answer is still generating (FIN-75): neither a real server nor the mock answers 404 for an answer it is still
generating, only for an unknown or replaced one, which the chat never watches — the state needs a server that lost
the answer mid-generation; `ChatViewModelBehaviourTest` covers the re-read of the history after such an error.
A false «сессия истекла» after the user's own sign-out (FIN-74) needs the session observer's coroutine to get the
`null` only after the sign-out returned and the phone screen read the reason — a thread interleaving neither the
emulator nor the mock can force; `SessionRepositoryImplTest` reproduces it with a token store that delivers the
`null` late.
A passcode left behind by a signed-out session (the process died between the sign-out and the passcode reset) and
cleared while the lock is starting (FIN-87) cannot be forced either: the flow cannot kill the process inside that
window, and the stale state needs a DataStore collector to start while the reset is being written, a timing the
emulator does not reproduce on demand (about 8 % of starts on the JVM). `AppLockStoreRaceTest` repeats that start
200 times; `profile/theme_and_logout` covers the plain sign-out.
A screen that stays put after a sign-in or a sign-out (FIN-88; the FIN-63 stall on the call screen) cannot be forced
either: it needs a subscriber of the session to start while the token store is being written, a timing the emulator
does not reproduce on demand (about 5 % of such starts on the JVM). `EncryptedTokenStoreRaceTest` repeats both writes
200 times against the real store; `auth/sign_in_existing_user` and `profile/theme_and_logout` cover the plain paths.
