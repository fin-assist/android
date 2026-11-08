# Qase73: карта сохранения покрытия перед следующим срезом

[FIN-476](https://finassist.youtrack.cloud/issue/FIN-476), продолжение
[FIN-473](https://finassist.youtrack.cloud/issue/FIN-473). Сверка исходников выполнена на
`release/0.2.20`, `25c878053e095bae53f7a5d921065a3c641827e0`.
Эта задача меняет только документацию. Текущий flow, assertions, бюджеты, mapping и runtime сохранены.

**Решение: NO-GO сокращению Qase73 сейчас.** Существующие тесты покрывают отдельные правила,
но пока не доказывают эквивалентность всей комбинации через настоящие UI callbacks. Допустим следующий
ограниченный PR с недостающим companion и обязательной связью с Qase73; его реализация не входит в FIN-476.
Полный Maestro остаётся обязательным до успешной проверки замены и отдельного решения о сокращении.

В [исходном полном прогоне 42506253547](https://github.com/fin-assist/android/actions/runs/42506253547)
зафиксированы PASS97/97 компонентов и Qase73=259,621с. Это одна успешная выборка, не измерение выигрыша
от переноса и не доказательство нестабильности. Историческая проблема видимости final Year
[FIN-444](https://finassist.youtrack.cloud/issue/FIN-444) уже исправлена: сохраняем проверку текста года,
а не объявляем новый дефект по длительности. Факт прогона дан в FIN-476; заново он здесь не запускался.

## Источники и границы доказательства

- [Текущий flow `period_change_retry.yaml`](../../.maestro/flows/analytics/period_change_retry.yaml)
  состоит из двух независимых clean-state UI входов. Вторая ветка удерживает команду через
  `network_delay_ms=20000`; это создаёт окно для реального жеста и выбора Year до ответа PUT.
- [AnalyticsViewModelTest](../../feature/analytics/impl/src/test/kotlin/ru/finassist/pf/feature/analytics/impl/ui/AnalyticsViewModelTest.kt)
  — настоящий VM, fake `AnalyticsApi`, `TestCoroutineScheduler`. Большинство примеров используют `data()`
  с placeholder-блоком `UNKNOWN`, а не полный финансовый September response.
- [AnalyticsRetryCallbackTest](../../feature/analytics/impl/src/test/kotlin/ru/finassist/pf/feature/analytics/impl/ui/AnalyticsRetryCallbackTest.kt)
  — настоящий `AnalyticsContent`, счётчики в `Handlers`, поданное извне состояние; VM не подключён.
- [AnalyticsPeriodControlTest](../../feature/analytics/impl/src/test/kotlin/ru/finassist/pf/feature/analytics/impl/ui/AnalyticsPeriodControlTest.kt)
  — настоящий control/layout/semantics; handlers сами изменяют `shown`. Это не прохождение VM/API.
- [AnalyticsReliabilityComponentTest](../../feature/analytics/impl/src/test/kotlin/ru/finassist/pf/feature/analytics/impl/ui/AnalyticsReliabilityComponentTest.kt)
  — существующий подход для real `AnalyticsContent` + real VM с управляемыми ответами API.
  `opened()` освобождает предыдущие `ViewModelStore`; `render()` подписывает content на VM state.
  Используется [September fixture](../../feature/analytics/impl/src/test/resources/analytics/september.json),
  где платёж «Спортзал»; mock-ledger Maestro показывает РБК. Эти fixture нельзя выдавать за одинаковые данные.
- [AnalyticsScreen](../../feature/analytics/impl/src/main/kotlin/ru/finassist/pf/feature/analytics/impl/ui/AnalyticsScreen.kt)
  связывает `analytics.period.retry` с `retryPeriodChange()`, сегменты с `setPeriodType()`, блоки с `onAction()`.
  [AnalyticsViewModel](../../feature/analytics/impl/src/main/kotlin/ru/finassist/pf/feature/analytics/impl/ui/AnalyticsViewModel.kt):
  `onResume()` не читает при сохранённом failed target; `retryPeriodChange()` возвращает сохранённый triple;
  `startLoad()` не запускает GET при in-flight PUT; `dismissRegular()` различает известный отказ и неизвестный исход.
- [MockAnalyticsApi.dismissRegularPayment](../../mock/backend/src/main/kotlin/ru/finassist/pf/mock/api/MockAnalyticsApi.kt)
  считает реальную попытку до задержки; [MockErrorCatalog.injectedError](../../mock/backend/src/main/kotlin/ru/finassist/pf/mock/MockErrorCatalog.kt)
  задаёт для `unknown_code` именно `AppError.Api(FUTURE_OPERATION_FAILURE, 422)` с wire user message.
  [E2eControlActivity](../../app/src/mockE2e/kotlin/ru/finassist/pf/e2e/E2eControlActivity.kt) проверяет накопленный
  injected error count, [MockUiEvidence](../../mock/backend/src/main/kotlin/ru/finassist/pf/mock/MockUiEvidence.kt)
  — число dismissal attempts. Сброс `error_operation` счётчики не обнуляет.
- [Текущая связь Qase](../../scripts/qase-cases.json): `.maestro/flows/analytics/period_change_retry.yaml` →73.
  Обязательного JVM-компонента73 пока нет. Правила слоёв — [testing](../testing.md),
  [E2E](../e2e.md), [карта предыдущего пилота](fin473-pilot-coverage.md).

Прямой вызов VM или событие репозитория проверяет потребителя, а не producer UI, Android lifecycle,
жест, сетевой transport или mock request boundary. Mock flavor работает in-process; даже Maestro здесь
не доказывает HTTP/SSE stage backend. Эти границы нельзя закрыть сходством названий тестов.

## Обязательства исходного flow

Номера A/B ниже обозначают фазы flow, а не новые Qase-кейсы. Каждая строка остаётся обязательной.
«Частично» означает, что существующий тест полезен, но удалять исходную проверку на его основании нельзя.

| Исходное действие / oracle | Точное существующее покрытие | Пробел и обязательный будущий владелец |
| --- | --- | --- |
| A/B: clear-state, документ готов, полноценный UI login/passcode, Analytics → «Октябрь 2026», prev → «Сентябрь 2026» | Maestro и вызываемые `clear_state`/`login`; VM `the tab root asks for the server's default period and adopts the answer's parameters`, `the period route opens the given parameters` — только запросы/параметры | Сохранить реальные вход, навигацию и переход месяца в Maestro; VM init не заменяет producer UI. Два reset сейчас не объединять. |
| A: Month checked=true и `analytics.block.tiles` после выбора September | Maestro; VM `a failed switch retains visible data without a transient snackbar` проверяет date/no snackbar | Companion должен отрисовать реальный September, проверить Month/header/tiles и равенство полного снимка. Maestro проверяет наличие tiles, но исходный Q73 **не содержит числового oracle** сумм; не приписывать ему соседние финансовые проверки. |
| A: offline Year создаёт durable «Не удалось сменить период» и `analytics.period.retry`, сохраняет September и Month checked=true | VM `a failed period switch keeps the shown period and says so`; `a failed switch retains visible data without a transient snackbar` | Нет combined Content→VM проверки видимой period notice, Retry и сохранённого финансового content. Добавить companion; настоящий offline request/видимая ошибка остаются Maestro. |
| A: September интерактивен; реальное «Не подписка» получает unknown-code сообщение, period error и Retry не исчезают | VM `a dismissal failure cannot relabel or consume the pending period error`: Server500 period + Offline dismissal, сохраняет presentation после `consumeSnackbar()` | Это соседняя пара ошибок, не offline period + known API refusal с пользовательским unknown-code текстом. Нужен combined companion с реальным block-button→VM callback, обеими видимыми ошибками и неизменным полным September. Producer/button и unknown-code mock boundary сохранить в Maestro. |
| A: `expect_analytics_dismiss_requests=1` после ошибки | Только Maestro predicate; упомянутый VM test не считает попытки: fake бросает `dismissFailure` **до** добавления id в `dismissed` | Сохранить exact guard. Companion должен считать входы fake API до исключения, проверять paymentId/date/transferMode и отсутствие скрытого повторного PUT; это отдельный oracle, не замена mock счётчика. |
| A: возврат из control Activity не заменяет September и period notice; после scrollUP виден Retry | VM `explicit period retry preserves the failed target and resume never retries it`: после `onResume(null)` ровно2 GET; `a dismissal failure cannot relabel or consume the pending period error` — другая отдельная последовательность | Объединить resume **после** двух ошибок в companion: zero additional GET/PUT и тот же снимок/presentation. Реальный Activity resume и сохранённая прокрутка остаются Maestro. В flow отсутствие авто-GET выводится по сохранённому состоянию, собственного exact GET-count guard нет. |
| A: явный `analytics.period.retry` открывает Year, header «2026», Year checked=true, исчезают period Retry и `ds.error.error` | VM `explicit period retry preserves the failed target and resume never retries it`: retry равен сохранённому Request, повтор после успеха не добавляет GET; `a dismissal failure cannot relabel or consume the pending period error` проверяет точный target/очистку period error | `AnalyticsRetryCallbackTest` **не нажимает** `analytics.period.retry`: методы `paused recalculation retry refreshes shown data instead of resetting a failed period`, `fullscreen offline retry refreshes the shown context`, `fullscreen preparation timeout retry refreshes the shown context` нажимают `analytics.retry`→refresh. Companion обязан нажать настоящий period Retry, получить exact failed triple и проверить оба исчезнувших UI error oracle. Сохранить сквозной retry/header. |
| A: Month после успешного Year возвращает именно September и checked=true | VM `explicit period retry preserves the failed target and resume never retries it`; `month quarter and year retain independent choices even outside the other tabs` | Нужен настоящий segment click после UI retry, а не вызов `setPeriodType()`; проверить September header, Month semantics и точные параметры GET. До замены оставить Maestro. |
| B: в готовом September нажать именно «Не подписка — РБК»; команда ещё не завершена при выборе Year | Maestro: delay20s, реальный dismiss click, scrollUP, отсутствие сообщения отказа перед Year tap | `period selection during a refused PUT is read after the command finishes` держит fake через `CompletableDeferred`, но вызывает VM напрямую. Companion должен нажать настоящий item button, удержать PUT, показать неизменный September, проверить отсутствие premature error/GET и нажать настоящий Year. Сохранить producer/gesture/in-flight окно Maestro. |
| B: известный отказ PUT освобождает отложенный Year; не требуется второй Year tap или Retry | VM `period selection during a refused PUT is read after the command finishes` + helper `deferredSelection`: `NOT_FOUND`404, до release1 GET, после release ожидаемые data/target; `dismissed == [first]` | Это релевантный known refusal, но не точная unknown-code fixture и не real UI wiring. `qase89UnknownReadRecoveryAndDeferredSelection` проверяет **UNKNOWN** Server500: no auto Year, возврат Month, второй tap/recovery — противоположная ветка, не замена. Нужен Q73 companion с известным отказом, release и ровно одним автоматическим deferred GET. |
| B: `analytics.period.rolling_year` виден; после отдельного scroll к text «2026» виден Year header, Year checked=true, period Retry отсутствует | `AnalyticsPeriodControlTest`: `only the current quarter or year offers its right aligned rolling choice`; `a wide period row shows all four full labels and their selection semantics`; `a narrow large-text period row reveals selection and preserves manual scrolling` | Это state/semantics/handler tests, не финал задержанного VM перехода. Companion должен проверить real final content/Year semantics/отсутствие Retry. Android viewport и scoped year-text scroll, исправленный FIN-444, остаются Maestro; наличие parent picker не заменяет видимость текста. |
| B: final config требует `expect_error_requests=1` **и** `expect_analytics_dismiss_requests=1`, затем сбрасывает delay/error | Только реальный mock boundary + control receipt. VM `deferredSelection` считает один payment id, но не injected failure counter и не transport | Оба exact1 guard остаются обязательными в Maestro. Companion проверяет собственные read/write request records; они не подменяют контроль mock injection и не оправдывают удаление guards. |

Дополнительные уже существующие VM regressions не нужно дублировать ради Qase: `an original request refusal
blocks period retry but not an explicit refresh of the shown month`, `a period retry obeys the captured server
delay without automatic submission`, `a different selection retires a failed target and its retry action`.
Они защищают refusal/delay/new intent, но сами не доказывают сохранение всех видимых состояний Q73.

## Минимальное недостающее комбинированное покрытие

Предлагаемый **новый, пока не реализованный** метод
`AnalyticsReliabilityComponentTest#qase73PeriodRetryAndDeferredRefusal` использует существующие
`render`/`opened`, fixture, real VM, Compose rule и управляемый fake API. Новый harness, сервис,
native suite, production seam, sleep или инфраструктура не нужны. Внутри две изолированные фазы:

1. September → реальный Year click → Offline → оба September/Month/tiles oracle и period notice;
   реальный dismiss click → известный unknown-code API отказ → независимые ошибки;
   resume без новых запросов; настоящий period Retry с exact triple → Year/header/selected/нет ошибок;
   настоящий Month click → remembered September. На каждом отказе сравнить весь исходный September response,
   включая RUB, source zone, ranges, blocks и суммы; видимые tiles и платёж остаются доступны.
2. Новый VM/store с September; реальная dismiss-кнопка → held PUT → реальный Year click;
   ноль GET во время PUT, один PUT, сохранённые деньги/Month, отсутствие преждевременной ошибки;
   release **известным отказом**, один deferred Year GET с исходной датой September и transfer mode;
   held GET завершается корректным [Year response](../../feature/analytics/impl/src/test/resources/analytics/year_2026.json),
   затем видимые Year header/selected/rolling choice, нет period Retry.
   Отдельно проверить no hidden PUT/repeated GET. Нельзя завершить Server500/Offline и назвать это known refusal.

Exact target — равенство фактически неудавшемуся request triple, а не только `period == YEAR`.
API допускает более точный month key при coarsening: текущий `deferredSelection` ожидает
`YEAR/date=2026-09`, хотя successful response нормализован в `YEAR/date=2026`.
Не заменять ожидание запроса нормализованной датой ответа.

Fake-компонент доказывает удержание ответа и callback-переходы, не финансовые расчёты.
Контрактные единицы/знаки/RUB, source-zone границы, deduplication, own transfers и refunds нельзя менять
ради этого среза. Полное равенство fixture не выдаётся за новый расчёт mock-ledger или HTTP acceptance.

## Ограниченный diff-план следующего PR

1. Добавить только указанный метод и необходимые request records/controlled replies в существующем
   `AnalyticsReliabilityComponentTest`; не менять production поведение или соседние companions.
2. После успешного локального выполнения добавить обязательный ключ
   `jvm:ru.finassist.pf.feature.analytics.impl.ui.AnalyticsReliabilityComponentTest#qase73PeriodRetryAndDeferredRefusal`
   →73 в существующий `scripts/qase-cases.json`. Текущий `.maestro/…/period_change_retry.yaml`→73 сохранить.
   В `scripts/test_qase_report.py` явно добавить пару73 в whitelist `CaseMapTest.test_case_ids_are_unique_except_declared_complementary_ui_checks`;
   общий запрет неожиданных дублей сохранить. Проверить `CaseMapTest` и полный inventory:
   missing/failed JVM компонент не должен давать Qase73 PASS.
   Существующий targeted job в [e2e.yml](../../.github/workflows/e2e.yml) уже запускает и копирует XML этого
   класса; новый класс/job/registry не требуется.
3. Обновить эту карту и строку Qase73 в `docs/e2e.md` с фактически выполненными проверками;
   в `AGENTS.md`/`docs/testing.md` отразить расширение именованного пилота на73. Согласовать описание
   обязательных слоёв в существующем Qase73 без нового ID, потери исходных шагов или истории.
   **Первый такой PR сохраняет весь flow**, обе login/reset фазы, delay20s, все финансовые/visible assertions,
   real resume, producer gestures и request guards. Компонентное покрытие сначала становится обязательным.
4. Только после принятой замены и отдельно разрешённого сравнения рассматривать небольшой следующий diff:
   повторные проверки комбинаций ошибок/состояния могут переехать в companion; каждый удаляемый oracle
   получает точную строку карты и PASS нового владельца. Обычный offline→explicit retry и отложенный known
   refusal через реальные producer UI/mock guards остаются сквозными. Если это не сокращает измеренный
   критический путь, оставить полный E2E и закрыть кандидата с NO-GO оптимизации.

Здесь нет обещания убрать второй login или delay20s: они обеспечивают независимую начальную среду и окно
реальной конкуренции. Замена delay на меньший timeout без доказательства in-flight precondition запрещена.
Нельзя считать Qase82/89 отдельной заменой этой producer+subscriber комбинации.

## Проверки и критерии перехода

В FIN-476 выполнена сверка source/flow/issue; UI, AVD, Gradle, design-check и Actions dispatch/rerun
не выполняются. Приведённые далее команды — план **будущего implementation PR**, не отчёт об исполнении.
Все процессы запускаются из корня checkout с реальным process-group deadline.
Локальный wrapper `/private/tmp/fin473-bounded.py` принимает `SECONDS COMMAND...` и останавливает дочерние
процессы; на другой машине нужен эквивалентный process-group deadline, один tool yield его не заменяет.

```bash
# Новый метод + соседние real-VM companions и unit/callback/control regressions, debug и release; 900s.
python3 /private/tmp/fin473-bounded.py 900 ./gradlew \
  :feature:analytics:impl:testDebugUnitTest \
  --tests '*AnalyticsViewModelTest' --tests '*AnalyticsRetryCallbackTest' \
  --tests '*AnalyticsPeriodControlTest' --tests '*AnalyticsReliabilityComponentTest' \
  :feature:analytics:impl:testReleaseUnitTest \
  --tests '*AnalyticsViewModelTest' --tests '*AnalyticsRetryCallbackTest' \
  --tests '*AnalyticsPeriodControlTest' --tests '*AnalyticsReliabilityComponentTest' \
  --no-daemon --console=plain

# Existing inventory/report regression tests, без устройства; 120s.
python3 /private/tmp/fin473-bounded.py 120 python3 -m unittest discover -s scripts -p 'test_qase_report.py'
python3 /private/tmp/fin473-bounded.py 120 python3 -m unittest discover -s scripts -p 'test_e2e_shards.py'
python3 /private/tmp/fin473-bounded.py 60 python3 scripts/agent-docs.py --check

# ТОЛЬКО после отдельного разрешения UI: owned clean API34 Pixel6, 1800s весь build+flow.
# Используется absolute --flow; без --no-build, чтобы не принять чужой APK.
python3 /private/tmp/fin473-bounded.py 1800 scripts/e2e.sh \
  --flow "$PWD/.maestro/flows/analytics/period_change_retry.yaml"
```

Новый Compose companion — новый UI тест: по процессу проекта его локальный PASS обязателен до создания
implementation PR; запрет исполнения UI в FIN-476 этим не снимается. Debug/release JUnit XML должны содержать
новый метод без skipped/failure. Полный класс перепроверяется, если менялись shared fake/render/store helpers.
Для изменённого Maestro нужны локальный PASS до PR, screenshot/hierarchy для final year-text oracle,
execution receipt, source SHA, APK SHA256, CLI/image/viewport и оба exact1 predicate.
Не добавлять PR emulator workflow, метку `e2e`, quarantine или авто-retry assertions.

Для будущего сравнения отдельно согласовать одинаковые source/APK/environment, selection и бюджеты,
сохранить все попытки и фазовые времена setup/login/interaction/controlled wait. Сопоставлять обе ветки
Q73 и longest-shard budget, а не только время нового JVM-метода. Без сопоставимых измерений выигрыш неизвестен.
PASS одного прогона доказывает его функциональный результат, а не стабильность серии.

Перенос остаётся NO-GO, если потерян любой oracle из таблицы, обязательный component mapping/отчёт отсутствует,
known refusal заменён UNKNOWN, не подтверждены exact request targets/counts или только unit PASS выдан за
успешную Android UI границу. При неопределённости исходные проверки сохраняются.
