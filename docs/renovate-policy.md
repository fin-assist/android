# Автоматизация Renovate (FIN-211)

Контроллер `.github/workflows/renovate-policy.yml` работает с установленным Mend Renovate GitHub App. Сам Renovate продолжает создавать обновления; `automerge: false` сохраняется: слияние выполняет контроллер через обычный GitHub REST API с ожидаемым head SHA, без административного обхода защиты. Команды владельца для обычных PR и release → main сохраняются.

## Ветки и защита

Ровно одна цель: самая новая `release/X.Y.Z`, для которой ещё нет одновременно смёрженного PR из **этого репозитория** в `main` и подтверждения предка через compare API. Если таких веток нет — `main`. Только что созданная от main release-ветка остаётся активной до merge PR. Контроллер обновляет `policy.json` в отдельной ветке `renovate-policy`; рядом `base.json` — точная копия reviewable `renovate-base.json` из выбранной целевой ветки. Источник читается по закреплённому commit SHA только как JSON, код контроллера всегда из default branch. `renovate.json` подключает оба presets в порядке baseline → generated target/quarantine. Ветка содержит только три data JSON: `base.json`, `policy.json` и защищённые receipts `state.json`, без кода или workflows. Завершение CI, ручной запуск trusted workflow и получасовая сверка пересчитывают выбор. Привилегированные create/push/delete triggers исключены: сам controller job не запускает определение workflow из непроверенной ветки. Hosted Renovate подхватывает preset на следующем запуске; старые PR в прежнюю цель автоматически не сливаются и не закрываются.

Требуются `strict` required status checks с привязкой к GitHub Actions App (15368) и `enforce_admins: true` для **main и release/**. Push разрешён только `controllerLogin`, teams/apps пусты; wildcard `release/*` дополнительно `block_creations: true`, чтобы первоначальный push новой release с чужим baseline не обходил ограничения. Baseline принадлежит владельцу; наличие strict checks само по себе не доказывает проверку произвольного initial branch commit. Runtime проверяет owner-only target protection до публикации baseline/цели, включая единственную привязку каждого настроенного check к App15368 и явные запреты force-push и удаления ветки; отсутствующие или неизвестные значения блокируют записи (FIN-448). Имена CI — `.github/renovate-policy.json`, дополнительно обязательны `Renovate policy tests` и `Renovate runtime contract`. Другие существующие ограничения сохраняются, обходов для токена нет. Активные repository/organisation rulesets пока не поддержаны: runtime читает inherited rulesets и консервативно блокирует автоматизацию при active/неизвестном enforcement, чтобы owner actor не обходил дополнительные требования. Disabled/evaluate rulesets не заменяют classic strict checks. Пустой, отсутствующий, pending, skipped или cancelled обязательный check блокирует действие. Проверки текущей попытки PR CI отделены от одноимённых skipped jobs push CI. Чужие дополнительные обязательные проверки также учитываются.

При первом чтении PR, перед merge/retry/close и после чтения policy непосредственно перед записью quarantine/recovery повторно проверяется отсутствие конфликтов: нужны `mergeable: true` и известное неконфликтное состояние. Если конфликт или неизвестная mergeability обнаружены до claim/quarantine, PR остаётся открытым без этих записей и расходования retry budget. Если claim уже сохранён до появления конфликта, бюджет остаётся потраченным: dispatch блокируется, уже выполняемая обработка claim объясняет остановку в комментарии, повторная доставка и восстановление mergeability без нового attempt не разрешают второй retry. При восстановлении уведомления/receipt для уже потраченного budget без нового attempt также требуется свежая проверка PR, CI и отсутствия конфликта. Проверки и записи — отдельные REST-запросы, общей транзакции GitHub API не предоставляет. Известные `blocked`/`behind` при красном CI допускают прежний повтор и подтверждённое закрытие; слияние по-прежнему требует `clean`/`unstable` (FIN-448).

Перед слиянием повторно читаются PR, все коммиты, текущий CI run/attempt и выбранная ветка. GitHub дополнительно проверяет head SHA и защиту ветки. Это несколько REST-запросов: API не предоставляет общей транзакции выбора релиза и merge; административная смена ветки между последним чтением и merge не атомарна. Контроллер не ставит PR в отложенную native auto-merge очередь.

## Только полностью ботские PR

Автор — проверенный Renovate bot ID 29139614, ветка `renovate/` в том же репозитории. Проверяется полный список коммитов (до 250; больше — ручная работа), author ID, действительная подпись и signer=committer `web-flow` или Renovate. Человеческий коммит блокирует автоматические действия, даже если сохранил bot author при cherry-pick. Имя ветки и метка `dependencies` сами по себе ничего не разрешают.

Все AndroidX/Compose обновления, включая смешанные группы, остаются на ручное слияние. Проверяются и metadata Renovate, и фактическое изменение version catalog, включая version.ref библиотек и plugins. Kotlin Compose compiler (`org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin:compose-compiler-gradle-plugin`) и смешанный Kotlin PR тоже требуют владельца. Ограничение fragment и существующая группировка сохраняются. UI-тесты контроллер не запускает.

## Повтор и закрытие

Только завершённый failure обязательного CI: контроллер сначала сохраняет общий claim `{sha, runs: {workflow: {run_id, attempt, retried}}}` в защищённом `state.json` с optimistic file SHA; ключ — PR+head SHA. Затем добавляет информационный комментарий и запрашивает по одному `rerun-failed-jobs` для каждого упавшего обязательного workflow (`CI` и `Renovate policy tests`). Успешный основной CI не скрывает падение отдельного workflow. Частичный повтор сохраняет предыдущие успешные jobs того же run/SHA; старый failure не заменяет отсутствующий job текущей попытки. Повторная доставка события не повторяет запрос. Изменение или удаление комментария не сбрасывает budget и не разрешает закрытие. Старые SHA receipts сохраняются даже при rollback/reopen; автоматической очистки нет. Missing/corrupt state.json и CAS conflict блокируют действие; создание пустого файла разрешено только явным bootstrap владельца до активации. Ошибка после сохранения claim оставляет budget потраченным и требует проверки владельца, а не слепого повторного запроса. Если следующий запуск по-прежнему видит завершённый failure прежнего attempt вместо ожидаемого retry, workflow остаётся красным с owner-action сигналом; это относится и к отказу POST комментария или записи его id. После ручной сверки владелец при необходимости запускает Re-run failed jobs; budget автоматически не сбрасывается. Падение той же SHA после точного следующего attempt каждого повторённого run разрешает закрытие; успешные workflow должны сохранить run/attempt. Успех повтора разрешает обычную проверку слияния. Смена SHA даёт новую независимую попытку; смена run или неожиданный номер attempt оставляет PR владельцу. Ошибка API после claim не приводит к слепому повтору: владелец проверяет run и при необходимости запускает его вручную.

Перед закрытием сохраняется ограничение **точных предложенных значений** зависимостей в generated preset: Dependency Dashboard approval, version-scoped `recreateWhen: never`, изоляция `groupName: null` и новый `additionalBranchPrefix` с номером закрываемого PR. Новая branch identity ещё не существует, поэтому сохранённая старая ветка не обходит запрос approval. Затем тот же комментарий получает проверенный SHA, провалившиеся checks и ссылки, результат повтора и способ возобновления. Контроллер ветку не удаляет. Минимальные структурированные metadata добавляет сам Renovate в `commitBody` каждого подписанного HEAD commit; контроллер читает их только после проверки подписи и SHA. Изменяемый PR body не разрешает действия и не задаёт quarantine. `prBodyNotes` содержит информационную копию. Старые PR без подписанных metadata остаются на ручную обработку. Большие release notes не включаются в metadata; настоящий renderer проверен с 21 обновлением и данными больше 1 MB.

Повтор той же версии: в Dependency Dashboard отметить Closed/Ignored или Pending Approval. Dashboard создаёт новую изолированную branch identity. Если повтор снова красный, следующий номер PR меняет prefix, и новая попытка снова требует approval. Новые значения версий не совпадают с этим правилом. Digest-обновления ограничиваются точным `newDigest` через `matchJsonata`, а не tag: новый digest того же tag не скрывается. Обычные неудачные точные значения временно изолируются из прежней группы; остальные и новые исправленные значения сохраняют исходные группировки. Это исключение нужно только для контролируемого повтора, а не меняет политику успешных обновлений.

Глобальный `recreateWhen` остаётся стандартным `auto`. После автоматического закрытия controller добавляет recovery rule с `recreateWhen: always` только для затронутой зависимости, datasource и `newMajor` (для digest без major — прежнего tag). Это возвращает исправленные версии той major-линии, которую Renovate иначе мог бы игнорировать целиком после закрытого PR. Recovery rule не меняет имена branches и не делит исходные группы. Более поздние exact failed-value rules задают `never`, approval и отдельный retry prefix: прежние неудачные значения не зацикливаются.

Ограниченный эффект этой политики: после появления recovery rule **любые вручную закрытые обновления той же зависимости, datasource и major** могут возобновляться. Для явного отклонения этой family нужно удалить её recovery rule через reviewable PR к config-only ветке; для отдельного значения сохранить точное generated quarantine rule. Произвольное family approval/never rule не поддерживается валидатором и добавлять его нельзя. Другие зависимости и другие major сохраняют прежнюю семантику ignored. Точные автоматически провалившиеся значения всегда остаются за Dashboard approval. Fixture на настоящем Renovate из manifest/lock (исходная проверка 44.176.2) проверяет family recovery, сохранение групп, approval и смену retry generation. Hosted App ещё должен подтвердить загрузку preset на следующем запуске: это отдельная проверка активации, не свидетельство уже работающей автоматизации.

Для существующей группы FIN-184 `androidx compose stack` применяется особое правило после первого подтверждённого падения: весь исходный набор семи matcher-координат сохраняется одной группой (`separateMajorMinor: false`, ограничения fragment наследуются из baseline), получает единый retry prefix и один Dashboard checkbox, `automerge: false`. Per-package `groupName: null` к этим участникам не применяется. Все последующие версии стека остаются видимы вместе в Pending Approval, требуют совместного подтверждения и ручной UI-проверки; повторное падение меняет generation и требует нового подтверждения. Автозакрытие полностью ботского красного PR разрешено FIN-211 и не отменяет совместного обновления.

## Граница доверия generated presets

`renovate-policy` защищена отдельно: разрешён push только `controllerLogin` (сейчас `avtomon`), teams/apps пусты, `enforce_admins: true`, force-push/delete запрещены. Для этой служебной config-only ветки нет PR/CI requirements; требования main/release сохраняются. Runtime перед синхронизацией и любыми PR-действиями проверяет эту внешнюю защиту. Retry receipts имеют отдельную строгую data schema и тот же owner-only write guard; их отсутствие не трактуется как пустая история. `read_policy` и `write_policy` принимают только два верхнеуровневых поля и строго заданные generated rule shapes; посторонние `automerge`, `ignoreDeps`, изменения allowedVersions и произвольные JSONata/regex отвергаются. Оператор может удалить ошибочный recovery rule; новые нестандартные override rules этот preset не принимает.

## Подготовка и включение

1. Bootstrap generated ветки до мержа `renovate.json` (исходные правила вынесены в `renovate-base.json`, generated preset подключён после них): `GITHUB_REPOSITORY=fin-assist/<repo> RENOVATE_POLICY_TOKEN=<credential> python3 -I .finassist-ci/<revision>/common/renovate/renovate_policy.py --root "$PWD" --bootstrap`. Существующая ветка никогда не перезаписывается. Секрет передавать через окружение без записи значения в историю команд.
Целевая активная release-ветка должна содержать проверенный `renovate-base.json`. В release, созданные до внедрения FIN-211, перенести этот baseline до активации: отсутствие файла останавливает весь контроллер до любых generated writes с явным сообщением об имени ветки и файла.

2. Слить PR реализации по явной команде владельца. Проверить CI и оба review; затем установить строгие защиты на main/release с сохранением всех существующих правил. В `controllerLogin` записать login владельца fine-grained PAT (сейчас `avtomon`). GitHub App installation token эта реализация не поддерживает: ему нужны отдельная identity-проверка и push allowance. До завершения setup сохраняйте `RENOVATE_POLICY_ENABLED` отличным от точного `true`: permissionless gate завершает работу до Environment, credential и baseline/target sync. Права и включение остаются отдельными действиями владельца; существующие проверки не ослабляются.
3. Использовать `RENOVATE_POLICY_TOKEN` (fine-grained PAT владельца, чей login совпадает с `controllerLogin`); в текущей установке уже существующий organization secret, новый токен не нужен. Требуемые права на целевые репозитории: Contents, Pull requests, Issues, Actions, Workflows — write; Administration, Commit statuses — read; Metadata — read. Check-runs читает отдельный `GITHUB_TOKEN` с `checks: read`; этот токен не используется для mutations. Не давать bypass. `GITHUB_TOKEN` запрещён для merge: он не запускает необходимые post-merge workflows.
4. По явному решению владельца используется уже добавленный organization secret `RENOVATE_POLICY_TOKEN`: его текущая доступность подтверждена metadata во всех 14 репозиториях организации, включая bank-bff/infra без Renovate. Сохраняется существующая настройка; контроллер внедряется только в 12 репозиториев с Renovate. Сужение Selected repositories до этих 12 возможно отдельным решением владельца и не блокирует текущую работу. Новый PAT, копирование по репозиториям или перенос в environment secrets не требуются. Controller job остаётся привязан к Environment `renovate-policy` с custom deployment branch policy ровно `main`, тип branch. Важно: это ограничивает данный job, но organization secret доступен и другим workflows выбранного репозитория, включая workflow из feature-ветки при наличии write-доступа. Владелец явно принял эту границу доверия; environment-only secret остаётся возможным отдельным усилением, не условием работы. `allow_auto_merge` остаётся выключенным. После проверки hosted preset, прав токена и branch protections установить `RENOVATE_POLICY_ENABLED=true`.

Права Automation не ослабляют обычные PR. Токены Claude и чтения Go modules для этой задачи не переиспользуются. Доступ к org secrets может требовать отдельной организации/авторизации; отсутствие доступа не повод печатать значения секретов.

## Проверки

В GitHub Actions общий набор выполняет обязательная проверка `Renovate policy tests` через закреплённую публичную Action `fin-assist/renovate-actions`. Для локального запуска `python3 scripts/test_renovate_shared.py` получает commit из `.github/renovate-source.json` в `.finassist-ci/<revision>`, проверяет целостность checkout, manifest и action pins и запускает общий набор policy/lifecycle/CLI/gate. Источник checkout — `https://github.com/fin-assist/renovate-actions.git`. Локальный runner ограничивает дочерний процесс 120 секундами.

Настоящий Renovate и integrity транзитивных зависимостей закреплены в `scripts/renovate-runtime/package.json`/`package-lock.json`; версия не дублируется в командах. Используйте Node из текущего workflow/manifest. Установку `(cd scripts/renovate-runtime && npm ci --ignore-scripts --no-audit --no-fund)` ограничьте process timeout600s. Затем запускайте общий runner из проверенного cache: `python3 -I .finassist-ci/<revision>/common/renovate/test.py --root "$PWD" --mode runtime --runtime "$PWD/scripts/renovate-runtime" --source-revision <revision>`. Он сам задаёт пути к config, manifest и controller, запускает все три fixtures с timeout90s каждый и проверяет actual grouping/recovery/digests, metadata и совместное извлечение immutable source pins. Внешние mutation/network adapters заменены фейками. Native RE2 install выключен: JavaScript RegExp fallback явно сообщается и не доказывает hosted RE2/загрузку presets. Hosted App execution и обычная очистка веток Renovate проверяются отдельно; сам controller ветки не удаляет.

Официальные источники: [preset и ref](https://docs.renovatebot.com/config-presets/), [шаблоны metadata](https://docs.renovatebot.com/templates/), [закрытые major/group PR](https://docs.renovatebot.com/key-concepts/pull-requests/), [Dashboard](https://docs.renovatebot.com/key-concepts/dashboard/), [config](https://docs.renovatebot.com/configuration-options/), [merge REST API](https://docs.github.com/en/rest/pulls/pulls#merge-a-pull-request).

Опубликованные presets: [config-only ветка](https://github.com/fin-assist/android/tree/renovate-policy).

Проверка опубликованных refs настоящим resolver: `RENOVATE_RUNTIME=<temporary npm prefix> node .finassist-ci/<revision>/common/renovate/verify_renovate_presets.mjs "$PWD"`; ограничить процесс 180 секундами. Для повышения API-лимита можно передать отдельный read credential в `RENOVATE_TOKEN`; код его не выводит.

При `RENOVATE_POLICY_ENABLED`, не равном точному `true`, непривилегированный gate
останавливает controller до Environment, credentials и любых API-запросов: baseline/target sync,
merge/retry/close не выполняются. Hosted Renovate продолжает читать активный `renovate.json`;
новые source-pin managers не зависят от включения контроллера. Явный bootstrap выполняется
отдельно владельцем и всегда требует credential, не включая автоматизацию.

Разрешённые обновления GitHub Actions тоже могут сливаться после проверки подписанных Renovate commits и обязательного CI. Это доверие к установленному Renovate и разрешённым обновлениям зависимостей: обновлённый action затем выполняется в default branch с её credentials. Main-only Environment ограничивает controller job; выбранный organization secret не изолирован от других workflows выбранных репозиториев. Обновлённый dependency action в main также остаётся доверенным кодом.

Ошибка отдельного PR не останавливает проверку остальных: контроллер продолжает их обработку и завершает workflow ошибкой со списком проблемных номеров без вывода токенов. Необязательные jobs не дают права на закрытие и не подменяют необходимые checks.

Текущий проект использует default branch `main`: guard и checkout задают её явно и не зависят от наличия repository.default_branch в payload schedule. Переименование default branch требует согласованного изменения workflow и main-only Environment, иначе запуск блокируется. При достижении лимитов generated policy (1000 rules) или receipts (1 MB) контроллер прекращает действия с ошибкой; владелец вручную проверяет и архивирует историю по отдельной retention policy. Автоматическое удаление budget/quarantine запрещено. Текущее требование FIN-211 подтверждает повторное падение PR; здоровье base push CI не является дополнительным gate и не диагностирует причину двух failures.

Install scripts отключены намеренно: в этом fixture отсутствует native re2.node, движок — JavaScript RegExp fallback, что явно выводится тестом. Exact-value случаи с `-`, `+`, `~` проверяются настоящим Renovate matcher; это не свидетельство выполнения hosted RE2. Генерируемая grammar ограничена anchored escaped literals; bundled RE2 parser допускает ASCII non-alphanumeric escapes как literals, но hosted execution всё равно проверяется отдельно при активации. Native RE2 сборка/загрузка не включается скрыто ради зелёного fixture.

Если состав FIN-184 меняется в активной release раньше default branch, runtime останавливает ВЕСЬ контроллер до любых записей и обработки PR (baseline/target sync, merge, retry и close, включая прочие обновления): target matchers должны совпадать с доверенным кодом. Порядок владельца: сначала согласованный код и baseline в main, затем соответствующий baseline в активной release; между этими согласованными изменениями контроллер временно остановлен. Если сделать release первым и ждать release→main, остановка продлится весь этот интервал. Владелец согласует обновление кода default branch и reviewable baseline; сохранённое Compose quarantine правило старого состава мигрирует отдельно с сохранением approval/prefix, receipts не очищаются. Автоматической миграции неизвестной группировки нет. Cancelled/timed_out/skipped либо лишний ручной attempt оставляют PR владельцу; это известный результат CI, а не доказательство второго failure или неизвестного запроса dispatch.

Версия проверочного Renovate задаётся единожды в scripts/renovate-runtime/package.json и integrity lock; fixtures сверяют загруженную версию с этим manifest, не с отдельной жёсткой строкой. Обновление dependency не отключено: при несовместимости внутренних APIs сам fixture закономерно требует исправления, остальные политики группировки не меняются. Если несовместимое обновление fixture входит в общую группу, два failure приведут к закрытию и exact quarantine всех её участников, включая независимые Go/Actions updates. Владелец исправляет fixture и явно одобряет проверенный набор через Dashboard; отдельная группа или исключение зависимости здесь не вводятся. Локальный node_modules исключён из git.

Обычный failed-value gate действует только при отсутствующем/null newDigest, а tag-scoped digest recovery — только при существующем ненулевом newDigest. Эти два fixed JSONata predicates строго allowlisted. Recovery/quarantine имеют разные identities и recovery всегда применяется раньше: закрытый переход на tag18 не скрывает будущий исправленный digest того же tag после ручного обновления базы. Нормальный failed tag и точный failed digest сохраняют независимые approval gates.

## Общий контроллер Renovate (FIN-456)

С FIN-499 production-код и lifecycle/runtime-регрессии Android поставляет публичный `fin-assist/renovate-actions`.
Единый source pin этого репозитория — `.github/renovate-source.json` (`revision`); action refs обоих workflows
и `source-revision` test steps должны совпадать с ним. Config, baseline, runtime package/lock,
state и receipts остаются данными репозитория. После exact activation gate controller job
читает только main data и запускает pinned action, не scripts потребителя с policy token.

В командах `<revision>` означает полный SHA из manifest, а `<consumer>` — корень checkout.
До передачи mutation credential подготовьте и проверьте cache read-only командой
`python3 scripts/test_renovate_shared.py` с внешним process timeout.
Она использует побайтно совпадающий bootstrap с проверкой raw cache integrity; тесты не получают
`RENOVATE_POLICY_TOKEN`. После этого явный bootstrap выполняется кодом source:
`GITHUB_REPOSITORY=fin-assist/android python3 -I .finassist-ci/<revision>/common/renovate/renovate_policy.py --root "$PWD" --bootstrap`.
Credential передаётся через окружение; bootstrap не включает автоматизацию и не перезаписывает ветку.

Runtime runner того же source вызывается с явными данными и pin:
`python3 -I .finassist-ci/<revision>/common/renovate/test.py --root "$PWD" --mode runtime --runtime "$PWD/scripts/renovate-runtime" --source-revision <revision>`.
Обновление или откат возвращает manifest, все action refs/test inputs и matching bootstrap вместе.
Сначала source-коммит проходит ревью и merge с сохранением его identity; squash/rebase требует
repin итогового SHA и нового CI/review. Перенос не меняет credentials, protections или activation.

FIN-456: активный `renovate.json` содержит новые managers для source inputs; action refs
извлекает штатный `github-actions` manager. Прежние FIN-372 managers manifest/`ci.yml` и
группировки сохраняют исходное расположение. Там, где они приходят из `base#renovate-policy`,
совместное обновление зависит от уже опубликованного baseline с этими правилами: новый
root-manager сам не обновляет `base.json` и не заменяет недостающие preset rules.
Добавленные в специализированные проекты root manifest/group rules работают непосредственно из root.

## Active source-pin managers (FIN-456)

Новые `source-revision` inputs policy tests, `revision` сервисного release caller и
source manifest специализированных проектов извлекаются managers из активного
`renovate.json`. Они работают без включения write-controller и без baseline sync.
Добавлены только новые source inputs; прежние FIN-372 managers и группировки остаются
на своих исходных местах. `renovate-base.json` побайтно сохраняет содержимое до миграции;
старые правила не дублируются в root при объединении preset-массивов. Generated presets,
receipts, state и флаг активации этим исправлением не меняются.

## Пауза и проверка активации (FIN-456)

Флаг `RENOVATE_POLICY_ENABLED` задаётся на уровне repository (либо organization),
а не Environment. Permissionless gate читает только repo/org значение до входа
в Environment `renovate-policy`; одноимённая Environment-переменная этим gate
не поддерживается и не служит стоп-краном. Для паузы меняют именно repo/org флаг.

По сравнению с прежним sync-only режимом выключенного lifecycle синхронизация теперь
полностью остановлена. Изменение `renovate-base.json` или появление новой release-ветки
при паузе не обновляет опубликованные baseline/target и не вызывает ошибки guards/token.
Перед активацией владелец отдельно проверяет фактически опубликованные presets, исходную
группировку, credential scope и защиты. Явный `--bootstrap` требует credential и создаёт
только отсутствующую config-only ветку; существующие presets не перезаписывает и automation
не включает. Если baseline/target изменились во время паузы, их актуализация требует
отдельного контролируемого действия владельца либо следующего включённого reconciliation;
выключенный прогон это действие не заменяет. Включение допускает sync и lifecycle вместе.
