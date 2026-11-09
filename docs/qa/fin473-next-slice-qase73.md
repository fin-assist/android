# Qase73: дополнительное компонентное покрытие

[FIN-482](https://finassist.youtrack.cloud/issue/FIN-482) реализует карту
[FIN-476](https://finassist.youtrack.cloud/issue/FIN-476) после отдельного поручения владельца.
**Полный Maestro-flow сохранён без изменений.** Никакой oracle не перенесён из E2E и не удалён;
две login/reset-фазы, реальный resume, задержка20с, жесты, финансовые assertions и оба final exact1 guard
остаются обязательными. Таймауты не изменены. Сокращение flow требует отдельного решения и проверки.

В существующем `scripts/qase-cases.json` Qase73 требует оба компонента:

- `.maestro/flows/analytics/period_change_retry.yaml`;
- `jvm:ru.finassist.pf.feature.analytics.impl.ui.AnalyticsReliabilityComponentTest#qase73PeriodRetryAndDeferredRefusal`.

Результат кейса учитывает худший компонент; missing/skipped/failure JVM не даёт PASS даже при успешном
Maestro. Используются существующие inventory, targeted job и общий verdict. Новый case ID, job,
framework, production seam или второй реестр тестов не добавлены.

## Реализация и границы доказательства

Новый метод в [AnalyticsReliabilityComponentTest](../../feature/analytics/impl/src/test/kotlin/ru/finassist/pf/feature/analytics/impl/ui/AnalyticsReliabilityComponentTest.kt)
рисует настоящий `AnalyticsContent`, подписанный на настоящий `AnalyticsViewModel`. Входящие
ответы `AnalyticsApi` управляются fake объявленного интерфейса. `CompletableDeferred` удерживает PUT/GET,
`TestCoroutineScheduler` двигает виртуальное время; реальных sleeps нет. Для каждой из двух фаз создаются
новые API/VM, прежний `ViewModelStore` очищается. Сегменты, dismiss и period Retry нажимаются через
production Compose semantics/callbacks; lifecycle `onResume(null)` вызывается непосредственно.

Используются **полные неизменённые** versioned responses
[September](../../feature/analytics/impl/src/test/resources/analytics/september.json) и
[Year2026](../../feature/analytics/impl/src/test/resources/analytics/year_2026.json).
На удерживаемых/отказавших переходах сравнивается весь September: params, currency-bearing blocks/суммы,
source zone, ranges, state и calculation. Финансовые расчёты, deduplication, refunds и own-transfer exclusion
этим fake не вычисляются и не меняются. JVM fixture содержит «Спортзал»/`r1`; реальный mock-ledger E2E
сохраняет РБК. Различные fixture не выдаются за один и тот же ledger.

Известный отказ использует тот же wire shape, что `MockErrorCatalog.UNKNOWN_CODE`: `ApiError` с
`FUTURE_OPERATION_FAILURE`, HTTP422, `user_message` «Сервис получил новый тип ошибки. Попробуйте позже»
и `severity=warning`; presentation/retryability передаются в `ErrorResponseInfo`. Это не unknown outcome
Server500/Offline из Qase89. Feature не зависит от `mock/backend`.
Fake записывает полные GET/PUT параметры **до** возврата или исключения; отказавший PUT считается попыткой.

## Карта обязательств

| Обязательство | Дополнительный JVM oracle | Обязательный полный E2E |
|---|---|---|
| Готовый September/Month, tiles и платёж интерактивны | Полный September response; scoped header, Month selected, tiles displayed; реальная item-button | Полный UI login/passcode, Analytics→October→September, Month/tiles |
| Offline Year сохраняет September и durable period failure | Настоящий Year click, Offline GET; тот же response, видимые notice/period Retry, Month selected | Реальный offline request и September/error assertions |
| Known dismissal refusal не стирает независимую period error | Настоящая dismiss-button; видны wire user message и period notice/Retry; presentation и весь September неизменны | Unknown-code mock injection и обе ошибки после действия |
| Отказ PUT считается ровно один раз | Точная запись `id=r1/date=2026-09/transferMode=with`, сделанная до исключения; нет повторного PUT | Exact `expect_analytics_dismiss_requests=1` |
| Resume после двух ошибок не отправляет запросы | `onResume(null)` + virtual30с; GET records/count=2 и единственный PUT неизменны, оба notice сохранены | Настоящий Activity resume и retained scroll/state |
| Period Retry повторяет именно failed target | Настоящая `analytics.period.retry`→VM→API; полное равенство request triple, Year data/header/selected; period Retry и прежние ошибки исчезают | Реальный retry, header2026/Year selected/no error |
| Month восстанавливает September после Year | Настоящий Month click, точный MONTH/2026-09/with GET, полный September/header/selection | Прежний UI Month/September путь |
| Year выбирается во время незавершённого PUT | Реальная dismiss-button→held PUT→реальный Year click; PUT ещё pending, один initial GET, один PUT, нет premature refusal, September/Month/tiles сохранены | Delay20с и настоящие жесты/Year tap до отказа |
| Known refusal продолжает deferred selection автоматически | Release PUT известным422; без нового tap ровно один deferred Year GET, исходный September anchor, нет unknown-mutation branch | Unknown-code refusal, переход к Year без второго tap/Retry |
| Held Year не выдаётся за готовый ответ | Пока GET удерживается, остаётся полный September/header, Year header отсутствует; после reply полный Year response | Реальное завершение отложенного ответа |
| Финальный Year и отсутствие скрытых повторов | Scoped2026, Year selected, rolling-year choice displayed, нет period Retry/unknown notice; после virtual30с всё ещё2GET/1PUT | Scoped year-text scroll (FIN-444), Year semantics и no period Retry |
| Реальные mock counters и transport boundaries | Fake records не выдаются за эти проверки | Final `expect_error_requests=1` **и** `expect_analytics_dismiss_requests=1`, сброс delay/error сохранён |

Exact Year target — `YEAR/date=2026-09/with`, а не нормализованная дата ответа `YEAR/date=2026`.
Coarsening API допускает finer month key; проверяется фактически неудавшийся request triple.
Scoped period text ищется в unmerged semantics tree; перед visible oracle прокручивается сам текст,
а не только родительский picker/segment. Это компонентная подготовка viewport, не удаление visible assertion.

## Что остаётся сквозным

JVM callback-проверка не доказывает Android gesture/hit testing, Activity lifecycle, сетевой transport или
реальный mock injection counter. Эти границы остаются полностью в Maestro. `StatementsEvent` не
подменяет producer UI; в этом срезе событийная ветка не переносится. Mock E2E не является stage HTTP-приёмкой.
Прямые VM regressions, отдельные callback/control tests и Qase82/89 companions остаются обязательными
на своих уровнях; Qase89 с unknown outcome не заменяет known refusal Qase73.

Исторический baseline FIN-476: [42506253547](https://github.com/fin-assist/android/actions/runs/42506253547),
source25c87805, Qase73 PASS259,621с. Это прежнее наблюдение и не результат исполнения нового компонента.
Предыдущие FAIL/приёмка FIN-473 не переопределяются. Реализация FIN-482 не заявляет ускорение E2E:
его flow и бюджеты сохранены.

## Локальная проверка

Новый компонент до PR выполняется на существующем Compose/Robolectric стеке с JDK21/API35/native graphics,
без эмулятора. Команды из корня checkout с реальным process-group deadline:

```bash
python3 scripts/e2e-cache.py run-command --timeout 900 -- ./gradlew \
  :feature:analytics:impl:testDebugUnitTest \
  --tests '*AnalyticsViewModelTest' --tests '*AnalyticsRetryCallbackTest' \
  --tests '*AnalyticsPeriodControlTest' --tests '*AnalyticsReliabilityComponentTest' \
  :feature:analytics:impl:testReleaseUnitTest \
  --tests '*AnalyticsViewModelTest' --tests '*AnalyticsRetryCallbackTest' \
  --tests '*AnalyticsPeriodControlTest' --tests '*AnalyticsReliabilityComponentTest' \
  --no-daemon --console=plain
python3 scripts/e2e-cache.py run-command --timeout 120 -- python3 -m unittest discover -s scripts -p 'test_qase_report.py'
python3 scripts/e2e-cache.py run-command --timeout 120 -- python3 -m unittest discover -s scripts -p 'test_e2e_shards.py'
python3 scripts/e2e-cache.py run-command --timeout 60 -- python3 scripts/agent-docs.py --check
```

Фактические результаты, исходная ревизия и диагностированные неудачные попытки фиксируются в FIN-482/PR.
До успешного локального исполнения компонент не считается принятым. Полный Maestro, manual/full UI CI,
merge и сокращение E2E в этом этапе не выполняются. Post-merge main-приёмка98компонентов/92кейсов
остаётся отдельной проверкой после разрешённого владельцем merge.

Проверено локально до PR (FIN-482): новый Q73 standalone debug PASS16,109с; расширенная выборка
`AnalyticsViewModelTest`/`AnalyticsRetryCallbackTest`/`AnalyticsPeriodControlTest`/`AnalyticsReliabilityComponentTest`
—199debug+199release PASS. После удаления случайного дополнительного assertion из соседнего Q82
финальный класс перепроверен:4/4debug и4/4release PASS; тела прежних Q82/83/89 совпадают с базой.
Gradle запущен с `--max-workers=2`, каждый процесс имел прежний900с limit. Qase32 и inventory61 tests PASS,
agent-docs/KMP/diff checks PASS. Начальные авторские failures scoped semantics/viewport сохранены и
диагностированы до следующих запусков; временная reflection-диагностика удалена. Изменений production нет.
SHA256 неизменённого полного Q73 flow: `b36b58d50df8a8cf20a915197020a06088dc5d265f779a4e5a82423d2e18af3f`.
Эмулятор, полный UI workflow и новая публикация Qase не запускались; результаты main после merge ещё не получены.
