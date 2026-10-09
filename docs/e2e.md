# UI tests (e2e)

Two layers, both on an emulator or device:

| Layer | What | Where | Build |
|---|---|---|---|
| Maestro | User scenarios end to end: sign-in, passcode, operations, search, analytics, assistant, statements, profile, flags, offline | `.maestro/` | `mockE2e` |
| Native (Espresso / Compose test) | What Maestro cannot drive reliably — the system document picker | `app/src/androidTestMock/` | `mockDebug` |

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
step, commands, log), logcat, the preflight dump.

In CI (`.github/workflows/e2e.yml`) the tests run after every merge to `main`, on a PR labelled `e2e`
(add the label, push to re-run), and by hand from the Actions tab. The run summary lists the flows; reports are in the
`e2e-reports` artifact.

## Qase

Every scenario is a test case in Qase, project `PLAINFIN`, suite «UI-автотесты (Android)» (one sub-suite per feature):
steps and expected results in Russian, marked automated.

- `scripts/qase-cases.json` maps tests to cases: a Maestro flow by its path (`.maestro/flows/<feature>/<flow>.yaml`),
  an instrumented test by `<class>#<method>`.
- After a merge to `main` or a manual run, the `UI tests` workflow sends the results as one Qase run
  (`scripts/qase-report.py`: JUnit reports → run → bulk results → complete). The run title is
  `UI tests · <branch> · <sha>`, its description links the Actions run. PR runs and cancelled runs are not sent; a
  manual run from another branch is sent with that branch in the title.
- Statuses: passed, failed (an assertion `failure` and an unexpected `error` alike: in UI tests the latter is usually
  an app crash), skipped. A test found in several reports keeps its worst result.
- If the results cannot be recorded, the script retries once (429, 5xx, network) and otherwise deletes the empty run.
- The token is the repository secret `QASE_API_TOKEN`. Without it the step prints a notice and passes; a Qase error
  does not fail the job either.
- Check what would be sent without a token: `scripts/qase-report.py --dry-run build/e2e/maestro-report.xml`.

## The `e2e` build type

`assembleMockE2e` builds `ru.finassist.pf.mock.e2e`: the mock flavor plus test hooks from `app/src/mockE2e/`. It
installs next to `mockDebug` and never exists for `prod`.

- **Pinned date.** "Now" starts at 2026-10-08 12:00 MSK (`BuildConfig.E2E_NOW`, `ShiftedClock`) and runs from
  there. The fixture statement covers October 2025 — September 2026, so periods, amounts and limits stay the same
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

  Flags are read when a screen opens: set them before the screen (usually right after `clear_state.yaml`, before
  `launchApp`). Process-scoped values are set after `launchApp`.

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
| New step or branch between screens | the feature's flow walks the new path; a separate flow when it is a scenario of its own |
| Flag that changes behaviour | a flow with the flag switched (`config.yaml`), next to the default path |
| User-visible bug fix | a check that fails without the fix: the flow reproduces the bug |
| Copy, colours, layout only | none — the design check covers it |
| Logic without UI (parsing, accounting, API) | unit tests, not UI tests |

Maestro is the default. A native test (`app/src/androidTestMock`) covers what Maestro cannot drive — system
pickers, intents, permissions; keep it to that gap. When a scenario cannot run on the emulator, list it under
«Not covered yet» below with the reason and say so in the PR.

Every new or renamed test: a Qase case (suite of the feature, steps and expected results in Russian, automated),
a line in `scripts/qase-cases.json`, a row in «Coverage». The PR gets the `e2e` label so the flows run before merge.

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
| `operations/search_filters` | query, «Только расходы», amount, «Сбросить», period, result card |
| `analytics/overview` (smoke) | blocks, previous month, month/quarter/year, «Без переводов», category → operations |
| `analytics/blocks_switched_off` | flags hide tiles, categories, transfers chip, assistant (card and profile) |
| `assistant/ask_question` (smoke) | consent (error without the tick) → question → answer → limit 4 of 5 → chip → scoped search |
| `assistant/daily_limit` | unanswerable question is free; five questions exhaust the day |
| `statements/upload_errors` | CSV, not a statement, another bank; leaving the picker |
| `statements/upload_and_history` (smoke) | new operations; the same file again — nothing new; delete an upload |
| `profile/theme_and_logout` | dark theme; logout cancel / confirm with the notice |
| `profile/delete_account` | dialog → passcode (wrong, right) → «Аккаунт удалён» |
| `offline/no_network` | «Нет сети» on analytics and profile, «Повторить» after the network is back |
| native `StatementPickerTest` | upload through `ACTION_OPEN_DOCUMENT` (Espresso-Intents) up to the import result |

Not covered yet: voice input (needs a speech recognizer on the emulator), biometrics (needs an enrolled
fingerprint), the unread-lines screen (needs a test statement with unreadable lines), custom date ranges in search.
