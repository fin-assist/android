# Design check: экраны приложения против макетов Figma

Сравнивает экраны приложения с макетами файла Figma «Понятные финансы — макеты»
(https://www.figma.com/design/u8paWRq6KAT73UJ1x2jXLv/, страница MVP). Запускается агентом (routine на мерж в `main` или по
просьбе), в обычный CI не входит. Проверка неблокирующая: результат — отчёт и задача в YouTrack, а не красный
статус.

## Как устроено

| Шаг | Что | Где |
|---|---|---|
| 1 | Снимки экранов приложения с данными как на макете, 390 dp × xxhdpi | `./gradlew -Ppf.designcheck testDebugUnitTest` |
| 2 | Экспорт артбордов из Figma в PNG 1170 px (scale 3); список покрытия | `python3 design-check/export.py`, нужен `FIGMA_TOKEN` |
| 3 | Пары «макет / приложение / отличия», SSIM, сортировка от худших | `python3 design-check/compare.py` |
| 4 | Разбор пар и отчёт | агент |

Артборд — фрейм верхнего уровня файла Figma (прямо на странице или в секции верхнего уровня на ней; вложенные секции не читаются), имя которого — имя снимка:
латиница и цифры с заглавной буквы (`Main`, `SearchPeriod`, `MainDark`). Ширина — 390, высота — как у экрана на
макете. Остальные фреймы (листы дизайн-системы, черновики с русскими именами) артбордами не считаются. Имя
артборда в файле уникально: при повторе `export.py` останавливается и пишет, где оба. Экраны вне MVP лежат на
странице «Вне MVP» под теми же правилами; снимков у них пока нет, в покрытии они `covered: false`.

Токен — личный токен Figma (Settings → Security → Personal access tokens) с правом чтения содержимого файлов, в
переменной среды `FIGMA_TOKEN`. Коннектор Figma для экспорта не годится: он отдаёт картинку не крупнее 1×.

Снимки (шаг 1) — тесты `*DesignCheckTest` в `src/test` модулей фич с плагином `pf.screenshots`. Каждый
тест рисует содержимое экрана (`FeedContent`, `ProfileContent`, …) из готового состояния, без ViewModel,
через `DesignCheck.capture("<Артборд>", dark, heightDp, tab)` из `:core:screenshot-testing`. Имя снимка
совпадает с именем артборда в Figma — по нему находится пара. Системные панели — 24 dp сверху и снизу, как на
макетах (`LocalPfSystemBars`); у экранов вкладок рисуется нижняя панель, как в приложении.

Шторки (`PfBottomSheet`) и диалоги (`PfDialog`, выбор даты) открываются в отдельном окне, и снимок окна
активити их не видит. Для артбордов со шторкой или диалогом — `capture(…, popups = true)`: снимаются все окна
вместе со скримом. Состояние, в котором шторка открыта, задаётся в `…Content` так же, как в приложении.
У диалогов два артефакта снимка, это не расхождения: нет затемнения под диалогом (Robolectric не рисует
системный dim окна; скрим шторки рисует сама Compose, он есть) и ширина окна диалога может отличаться от
устройства.

Обычный прогон тестов (`testDebugUnitTest` в CI) эти тесты пропускает: им нужна среда Android целиком,
и рендер всех экранов долгий.

### Длинные экраны

Артборд выше телефона (больше 844 dp, например «Аналитика») рисует экран целиком, поэтому и снимок
снимается целиком: окно растёт, пока у экрана есть вертикальная прокрутка (`fullHeight`, по умолчанию
включён для таких артбордов). Артборд высотой с телефон — это видимая часть экрана, снимок той же высоты.
`compare.py` сравнивает картинки разной высоты по большей из них: блоки, которых нет на макете или в
приложении, видны на карте отличий, а не обрезаются; высоты пишутся в `scores.json`.

### Только затронутые экраны

`-Ppf.designcheck.changed=<файл>` — файл со списком изменённых путей от корня репозитория, по одному в
строке (`git diff --name-only`). Снимки модуля запускаются, если изменён сам модуль или любой модуль, от
которого он зависит, прямо или транзитивно (`:core:designsystem`, `:core:common`, `:feature:*:api`, …).
Изменения сборки (`build-logic/`, `gradle/`, `*.gradle.kts`, `gradle.properties`) и `design-check/`
запускают все снимки; пути вне модулей (`docs/`, `scripts/`) не запускают ничего. Точность — модуль: если
изменён один экран модуля, снимаются все экраны этого модуля. Тесты незатронутых модулей Gradle помечает
`SKIPPED`; при расчёте конфигурации (не при повторном использовании configuration cache) он ещё пишет
строки `design check: :feature:…:impl affected / not affected, skipped`.

Задачи тестов в режиме `-Ppf.designcheck` не бывают `UP-TO-DATE` и не берутся из build cache: снимки
пишутся в общий каталог в обход выходов задачи, и пропущенный прогон оставил бы его пустым.

Изменения макетов в Figma в репозитории не видны. После правок макетов — полный прогон (без списка).

### Покрытие

`export.py` пишет `build/design-check/mockups/coverage.json`: все артборды файла Figma (имя, страница, секция,
node id, размер), есть ли тест, который их снимает (`covered` — имя артборда первым аргументом `capture("…")` в
каком-нибудь `*DesignCheckTest.kt`), и снят ли артборд в этом прогоне (`rendered`). Артборды с `covered: false`
— экраны, которые ещё предстоит добавить. Ссылка на артборд —
`https://www.figma.com/design/u8paWRq6KAT73UJ1x2jXLv/?node-id=<node, «:» → «-»>`.

## Запуск вручную

```bash
# Container prerequisites: SDK, Maven mirror, Python libs
scripts/agent-setup.sh --design-check

# Start clean: compare.py pairs whatever PNGs are in the app dir
rm -rf build/design-check/app build/design-check/mockups build/design-check/compare
mkdir -p build/design-check

# App snapshots -> build/design-check/app/<Artboard>.png (all screens)
ANDROID_HOME=/opt/android-sdk ./gradlew -Ppf.designcheck testDebugUnitTest

# Or only the screens a change can affect
git diff --name-only origin/main...HEAD > build/design-check/changed.txt
ANDROID_HOME=/opt/android-sdk ./gradlew -Ppf.designcheck \
  -Ppf.designcheck.changed=build/design-check/changed.txt testDebugUnitTest

# Mockups for the same artboards + coverage.json -> build/design-check/mockups (FIGMA_TOKEN in the environment)
python3 design-check/export.py build/design-check/mockups

# Pairs and scores -> build/design-check/compare/<Artboard>.png, scores.json
python3 design-check/compare.py build/design-check/mockups build/design-check/app build/design-check/compare
```

## Как читать результат

- SSIM — только для сортировки: ниже 0.9 почти всегда есть заметные отличия, выше 0.95 — обычно мелочи.
- Расхождения в отчёте делятся на: вёрстка (отступы, размеры, порядок блоков), содержимое (тексты, набор
  строк и плиток), цвет и состояния. Для каждого — экран, что на макете, что в приложении, файл для правки.
- По умолчанию прав макет. Если приложение следует контракту API (`api.md`), а макет ему противоречит, —
  это отмечается в отчёте, решает человек.

## Добавить экран

Новый экран, новое состояние экрана, шторка или диалог подключаются к design check в том же PR, где появляются
в коде (правило в `AGENTS.md`).

1. Найти артборды экрана в `coverage.json` (`covered: false`) и снять каждый, тёмные `…Dark` тоже.
   Если артборда в Figma ещё нет:
   - имя — то, которое он получит, по образцу существующих: `Search`, `SearchPeriod`, `SearchDark`,
     `DeleteAccountDialog`. Тёмный — с суффиксом `Dark`; имя уникально среди снимков и буквально совпадает с
     аргументом `capture("…")`, иначе после появления артборда пара не найдётся. Дизайнер называет фрейм так же;
   - темы — базовое состояние в светлой и тёмной, остальные состояния в светлой, как в Figma;
   - данные — реалистичные, как в фикстурах мока; высота — по умолчанию (`PHONE_HEIGHT_DP`). У экрана с
     прокруткой — `fullHeight = true`, иначе снимок обрежется на 844 dp: при высоте по умолчанию окно само не
     растёт. В KDoc теста — что макета нет; после появления артборда фикстура и высота переводятся на его данные;
   - в PR — что макета нет. `export.py` пропустит такой снимок (`MISSING in Figma`), `compare.py` запишет в
     `scores.json` `"missing": "mockup"`.
2. Разделить экран на обёртку с ViewModel и `…Content(state, обработчики)`.
3. В `src/test` модуля — `<Экран>DesignCheckTest` с состоянием по данным артборда (числа, тексты, даты как
   на макете) и вызовом `DesignCheck.capture` для каждого артборда; имя артборда — буквальный первый
   аргумент `capture("…")`, по нему `coverage.json` считает покрытие. Даты — фиксированные, не `now()`.
   Высота — высота фрейма в Figma (`h` в `coverage.json`).
4. Если состояния артборда в приложении нет (экран устроен иначе), тест снимает ближайшее реальное
   состояние того же экрана и пишет об этом в KDoc; несуществующий в приложении экран не подключается.
5. В `build.gradle.kts` модуля — `id("pf.screenshots")`.

## Где расхождения

Баги — в YouTrack, проект FIN, в названии «Design check». Открытые задачи с таким названием — известные
расхождения; routine не заводит их повторно. Правки — отдельными PR со ссылкой на задачу; в PR — листы сравнения
после правки.

## Routine «Design check на мерж в main»

Создаётся на claude.ai/code/routines:

- **Репозиторий:** `fin-assist/android`.
- **Триггер:** GitHub event → Pull request → `closed`, фильтры «Is merged = true», «Base branch = main».
- **Окружение:** Network access → Custom, «Also include default list of common package managers» и в Allowed
  domains `dl.google.com`, `api.figma.com`, `figma-alpha-api.s3.us-west-2.amazonaws.com`,
  `finassist.youtrack.cloud` (или Full). Переменные среды: `FIGMA_TOKEN` — токен Figma (см. «Как устроено»),
  `YOUTRACK_TOKEN` — для вложений в задачу (шаг 10a). Setup script: `scripts/agent-setup.sh --design-check`.
- **Коннекторы:** YouTrack, Figma (для замеров при разборе отличий; картинки берёт `export.py`).
- **Промпт:**

```text
Design check for fin-assist/android after a pull request was merged into main. Work in the cloned repository
at main. Write the report, the YouTrack issue, the PR comment and your final answer in Russian.

1. Run scripts/agent-setup.sh --design-check.
2. mkdir -p build/design-check, then list the files the merged pull request changed, one path per line, into
   build/design-check/changed.txt:
   gh api --paginate repos/fin-assist/android/pulls/<PR number>/files --jq '.[].filename'
   (if that fails: git diff --name-only <merge commit>^1 <merge commit>). With no pull request in the event
   (manual run), skip this step and the -Ppf.designcheck.changed flag below: check every screen.
3. ANDROID_HOME=/opt/android-sdk ./gradlew -Ppf.designcheck -Ppf.designcheck.changed=build/design-check/changed.txt testDebugUnitTest
   PNGs land in build/design-check/app/. If there are none (no screen affected), comment on the pull request
   «Design check: изменения не затрагивают экраны с макетами» and stop.
4. python3 design-check/export.py build/design-check/mockups
   It exports from Figma file u8paWRq6KAT73UJ1x2jXLv every artboard (top-level frame) named like a PNG in
   build/design-check/app/, at scale 3, plus coverage.json. It needs FIGMA_TOKEN in the environment. If the
   token is missing or the Figma API keeps failing, comment on the pull request «Design check: не удалось
   выгрузить макеты из Figma (<причина>)» and stop.
5. Note what export.py printed: names "MISSING in Figma" go into the list «нет макета в Figma» (step 7);
   "width … ≠ 390" is a mockup problem, report it as a difference.
6. python3 design-check/compare.py build/design-check/mockups build/design-check/app build/design-check/compare
7. Look at every pair in build/design-check/compare/ (worst SSIM first) and list the differences per screen:
   layout, content, colour, states — where, mockup vs app, the file to change. Skip differences in data the
   fixture does not control, and two snapshot artefacts of dialogs: no dim behind a dialog, dialog window width.
   The mockup is right by default; if the app follows api.md and the mockup contradicts it, say so. Snapshots
   without a mockup (scores.json entries with "missing": "mockup") go into a separate list «нет макета в Figma»,
   not into the differences. Entries with "missing": "same width …" are a real problem (the snapshot width
   differs from the mockup), report them as differences. For a difference you need to measure, the Figma
   connector (get_design_context on the frame from coverage.json) gives the exact sizes and tokens.
   Leave out differences under 2 dp unless they are in a shared design-system component (core/designsystem),
   repeat on several screens or are visible to the user; skip line breaks that differ only because Figma and
   Skia render the font differently. For every difference you keep, say whether the user would notice it.
8. In YouTrack project FIN, read the open issues with «Design check» in the summary
   (query: project: FIN #Unresolved "design check"). Drop every difference they already describe.
9. Push the comparison images to the branch design-check/reports (create it as an orphan branch if it does
   not exist; never touch main), under <merge commit short sha>/.
10. If new differences remain, create one YouTrack issue in FIN: summary «[Android] Design check <short sha>:
    расхождения с макетами», Type Bug, Priority Normal, Subsystem android, Stage Backlog;
    description: the merged pull request, the screens checked with SSIM, the new differences as a checklist
    per screen, links to the images on the design-check/reports branch and to the Figma frame
    (https://www.figma.com/design/u8paWRq6KAT73UJ1x2jXLv/?node-id=<node from coverage.json, «:» → «-»>).
10a. For every difference, crop the same area of the screen from its comparison sheet (mockup on the left, app
    on the right; PIL is installed). Attach the crops to the new issue:
    curl -H "Authorization: Bearer $YOUTRACK_TOKEN" -F "file=@<file>.png" https://finassist.youtrack.cloud/api/issues/<id>/attachments
    and put each under its checklist item as ![](<file name>.png). If an upload fails (no token, 401), keep only
    the links to design-check/reports in the issue and say in the PR comment that the crops were not attached.
11. Comment on the merged pull request: screens checked, SSIM, and either the new YouTrack issue or «новых
    расхождений нет» with the open «Design check» issues that still apply.
Do not change application code.
```

Полный прогон после правок макетов в Figma — та же routine, запущенная вручную (без PR в событии), или отдельная по
расписанию с тем же промптом.
