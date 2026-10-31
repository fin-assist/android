# Feature flags registry

Source of truth for flag names. Code defaults live in `core/toggles` (`Flag` enum, `defaultValue`); the provider
(RuStore Remote Config in `prod`/`stage`, JSON asset in `mock` and `local`) may override them. Unknown or unreachable provider →
code default. Values are read at the screen boundary (when a screen is entered), not mid-screen.

Flags by flavor (FIN-120):

- `mock` — `:providers:toggles-local`: `app/src/mock/assets/flags.json` over code defaults (e2e:
  `app/src/mockE2e/assets/flags.json` and the control link).
- `local` — `:providers:toggles-local`: code defaults; a developer may put their own `app/src/local/assets/flags.json`
  (gitignored, same format as in `mock`). No debug screen yet: the e2e control link is mock-only.
- `stage` — `:providers:toggles-rustore`, only `pf.stageRustoreRemoteConfigAppId`; blank → code defaults.
  Production Remote Config properties never supply stage configuration.
- `prod` — `:providers:toggles-rustore`: RuStore Remote Config over code defaults.

Why `local` has local flags and not RuStore: whoever runs a local bank-bff should not depend on the RuStore console
or its keys, nor read flags meant for real users; and a `local` build stays out of production configs and cohorts
(it has no RuStore app id). The cost: Remote Config itself is not exercised in `local` — that is for `prod` and `stage` (with its separate app id).

Naming: `<feature>.<object>[.<detail>]`, lowercase, dot-separated. Same prefix is used for analytics events
(`<feature>.<object>.<action>`), see `core/tracking`.

| Flag | Default | What `false` does |
|---|---|---|
| `auth.registration` | `true` | New phone number after the call: instead of the consent screen, "Регистрация пока закрыта". Existing users still sign in. In `mock` the only "existing" number is the one in the mock config, and `app/src/mock/assets/flags.json` sets this flag to `false` (only that number signs in). The code default stays `true` on purpose: with RuStore unreachable and no cached config, registration is open (decision 06.10.2026). |
| `analytics.block.tiles` | `true` | Hides the tiles block (expense, income, balance, daily expense, forecast) |
| `analytics.block.expense_categories` | `true` | Hides "Расходы по категориям" and the "Все категории" screen for expenses |
| `analytics.block.income_categories` | `true` | Hides "Доходы по категориям" |
| `analytics.block.monthly_chart` | `true` | Hides the monthly charts |
| `analytics.block.regular_payments` | `true` | Hides "Подписки и регулярные платежи" (and "Не подписка") |
| `analytics.block.notable_spending` | `true` | Hides "Заметные траты" |
| `analytics.block.bank_fees` | `true` | Hides "Комиссии и проценты банку" |
| `analytics.block.small_frequent` | `true` | Hides "Мелкие частые траты" |
| `analytics.filter.transfers` | `true` | Hides the "С переводами / Без переводов" chip; requests always use `with` |
| `assistant` | `true` | Hides the assistant screen and every link to it: bottom tab, card on Analytics, profile row, consent switch, answer chips. Limit endpoint is not called. |
| `search.filter.period` | `true` | Hides the period chip and sheet in Search |
| `search.filter.category` | `true` | Hides the category chip in Search |
| `search.filter.amount` | `true` | Hides the amount chip in Search |
| `search.filter.kind` | `true` | Hides the "Только расходы" chip in Search |
| `statements.upload` | `true` | Hides the upload screen and every link to it (empty states, "Загрузить новую выписку", profile → only history). |
| `profile.delete_account` | `true` | Hides "Удалить аккаунт и все данные" |

`analytics.block.<id>` switches off the Analytics block with that `id` (api.md 6.1 «Блоки экрана», FIN-4): the server
composes the screen, the client drops the blocks switched off here. A block id without a flag in `Flag` (a new block
on the server, `balance`) stays on — add a flag here and in `Flag` to be able to switch it off. A section heading goes
together with the last of its blocks.

## Experiments

Variants of experiments over server-composed screens (FIN-9): a string under the key in the same provider (RuStore
Remote Config); registry — `Experiment` in `core/toggles`. The client sends the variant with the request (api.md 6.1
`exp=key:variant`) because the server composes the screen, and reports an exposure when a block is actually drawn
(`analytics.block.exposed` with `block` and `exp`), not when the config arrives. A value outside the list is the
default (first) variant. In `mock` the provider has no variants: the default one is in effect.

- `analytics_order` — variants `a` (default) and `b`: `b` puts the categories before the tiles on «Аналитика».

A filter that is hidden but arrives pre-filled from Analytics or an assistant chip is shown as a single
non-editable chip ("Как на „Аналитике“" / `selection_name`), as `docs/api.md` (fin-assist/contracts) describes
for filters without their own chip.

## Values

String values that are neither switches nor experiment variants; registry — `RemoteValue` in `core/toggles`, read
with `FeatureFlags.value`. Without a value from the provider the code default is in effect. In `mock` the provider
has none.

- `app.min_version` — minimum supported version, `MAJOR.MINOR.PATCH`; default empty (no minimum). Checked at start,
  before the first request, and with every new config (`MinVersionCheck`): below it the app shows the update screen
  (FIN-22). A later config without that minimum lifts the screen without a restart — a mistaken value in the console
  does not lock users out; the server's `CLIENT_VERSION_UNSUPPORTED` is final. An empty or malformed value never
  blocks. Until the provider has read its cached config at a cold start, the minimum of the last config seen is in
  effect (kept in the app's preferences), so the check still comes before the first request.

## Adding a flag

1. Add an entry to the `Flag` enum in `core/toggles` (default `true`, or `defaultValue = false`).
2. Add a row here.
3. Read it with `FeatureFlags.isEnabled(Flag.X)` at the screen boundary (view model init or `FeatureEntry`).
4. Run `python3 scripts/agent-docs.py --write` to refresh the flags block in `AGENTS.md`.

## Known limitation: account switch and RuStore cache

RuStore Remote Config serves its persisted config and syncs in the background (default interval 15 minutes).
After sign-in, sign-out or an account switch the provider asks for the config with the new `account`, but the
SDK may still answer from the cache fetched for the previous identity until its next sync. Toggles keyed by
user cohort can therefore lag up to one sync interval after an account change. If that matters for an
experiment, switch the client to `UpdateBehaviour.Actual` (every request goes to the server; cold start then
uses code defaults until the first answer).


FIN-397: MainLayer snapshots `assistant` once and provides that same value through `LocalAssistantEnabled`
to every Chat/Consent guard. Navigating away, opening consent and restoring a saved tab do not re-read a
live flag update independently from the bar. A new main layer reads the new flag value; disabled restored
entries redirect to Operations before screen/ViewModel creation, without assistant API calls. The local
default is false, and ownership stays in the app composition root; no mutable singleton is used.
