# Feature flags and events

Source of truth for flag keys: `core/toggles/.../Flags.kt`. This table must list the same keys (CI: `scripts/check-flags.sh`).

Provider: RuStore Remote Config in `prod` (`:core:toggles-rustore`), a local DataStore provider in `mock` (`:core:toggles-local`, editable on device: Профиль → «Флаги (mock)»). Defaults are in code and mean "everything on"; a provider can only turn things off or back on.

Identity: before login the provider gets the device id, after login — our `user_id` (then the config is re-fetched). New values apply at screen boundaries (next screen open), never mid-screen.

| Flag | Off means |
|---|---|
| `auth.registration` | A phone number unknown to the server cannot register: after the call the app shows "Регистрация пока закрыта" instead of the consent screen. Existing users log in as usual. In `mock` the only "existing" number is the one configured in the mock backend. |
| `analytics.block.tiles` | Tiles row (expenses, income, balance, daily, forecast) is hidden. |
| `analytics.block.expense_categories` | "Расходы по категориям" card is hidden. |
| `analytics.block.income_categories` | "Доходы по категориям" card is hidden. |
| `analytics.block.monthly_chart` | Monthly bar charts are hidden. |
| `analytics.block.regular_payments` | "Подписки и регулярные платежи" card is hidden. |
| `analytics.block.notable_spending` | "Заметные траты" card is hidden. |
| `analytics.block.bank_fees` | "Комиссии и проценты банку" card is hidden. |
| `analytics.block.small_frequent` | "Мелкие частые траты" card is hidden. |
| `assistant` | Assistant screen, analytics card, profile row and consent switch, answer chips — all hidden; `GET /v1/assistant/limit` is not called. |
| `search.filter.period` | Period chip and sheet hidden in search. |
| `search.filter.category` | Category chip and sheet hidden in search. |
| `search.filter.amount` | Amount chip and sheet hidden in search. |
| `search.filter.kind` | "Только расходы" chip hidden in search. |
| `analytics.filter.transfers` | "С переводами / Без переводов" chip hidden; analytics always requests `transfer_mode=with`. |
| `statements.upload` | Statement upload screen and every link to it hidden (empty states keep their text without the button; profile keeps only upload history). |
| `profile.delete_account` | "Удалить аккаунт и все данные" link hidden. |

When a flag hides a filter that an incoming `operations_filter` still carries (e.g. a category from analytics), the filter stays applied and is shown as a non-editable chip.

## Events (MyTracker)

Same naming scheme with an action suffix: `<feature>.<object>.<action>`, e.g. `statements.upload.started`, `statements.upload.completed`, `assistant.question.sent`, `analytics.period.changed`. No amounts, no personal data in parameters. User identity — our `user_id` via `Tracker.setUser`.
