# Feature flags registry

Source of truth for flag names. Code defaults live in `core/toggles` (`Flag` enum, `defaultValue`); the provider
(RuStore Remote Config in `prod`, JSON asset in `mock`) may override them. Unknown or unreachable provider →
code default. Values are read at the screen boundary (when a screen is entered), not mid-screen.

Naming: `<feature>.<object>[.<detail>]`, lowercase, dot-separated. Same prefix is used for analytics events
(`<feature>.<object>.<action>`), see `core/tracking`.

| Flag | Default | What `false` does |
|---|---|---|
| `auth.registration` | `true` | New phone number after the call: instead of the consent screen, "Регистрация пока закрыта". Existing users still sign in. In `mock` the only "existing" number is the one in the mock config, and `app/src/mock/assets/flags.json` sets this flag to `false` (only that number signs in). The code default stays `true` on purpose: with RuStore unreachable and no cached config, registration is open (decision 06.10.2026). |
| `analytics.block.tiles` | `true` | Hides the tiles row (expense, income, balance, daily expense, forecast) |
| `analytics.block.expense_categories` | `true` | Hides "Расходы по категориям" and the "Все категории" screen for expenses |
| `analytics.block.income_categories` | `true` | Hides "Доходы по категориям" |
| `analytics.block.monthly_chart` | `true` | Hides the monthly charts |
| `analytics.block.regular_payments` | `true` | Hides "Подписки и регулярные платежи" (and "Не подписка") |
| `analytics.block.notable_spending` | `true` | Hides "Заметные траты" |
| `analytics.block.bank_fees` | `true` | Hides "Комиссии и проценты банку" |
| `analytics.block.small_frequent` | `true` | Hides "Мелкие частые траты" |
| `analytics.filter.transfers` | `true` | Hides the "С переводами / Без переводов" chip; requests always use `with` |
| `assistant` | `true` | Hides the assistant screen and every link to it: card on Analytics, profile row, consent switch, answer chips. Limit endpoint is not called. |
| `search.filter.period` | `true` | Hides the period chip and sheet in Search |
| `search.filter.category` | `true` | Hides the category chip in Search |
| `search.filter.amount` | `true` | Hides the amount chip in Search |
| `search.filter.kind` | `true` | Hides the "Только расходы" chip in Search |
| `statements.upload` | `true` | Hides the upload screen and every link to it (empty states, "Загрузить новую выписку", profile → only history). |
| `profile.delete_account` | `true` | Hides "Удалить аккаунт и все данные" |

A filter that is hidden but arrives pre-filled from Analytics or an assistant chip is shown as a single
non-editable chip ("Как на „Аналитике“" / `selection_name`), as api.md describes for filters without
their own chip.

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

