# Design check: экраны приложения против макетов Claude Design

Сравнивает экраны приложения с артбордами холста «Понятные финансы» в Claude Design
(https://claude.ai/artifact/8ndjPbEdsxZKHPTxNWhePR). Запускается агентом (routine на мерж в `main` или по
просьбе), в обычный CI не входит. Проверка неблокирующая: результат — отчёт, а не красный статус.

## Как устроено

| Шаг | Что | Где |
|---|---|---|
| 1 | Файлы холста (`project/*` + `artifact-type/dc-runtime.js` → `support.js`) | читает агент инструментом Artifact в `build/design-check/site/` |
| 2 | Рендер артбордов в PNG 1170 px (390 css px × 3), шрифт Roboto | `node design-check/render.js` |
| 3 | Снимки экранов приложения с данными как на макете, 390 dp × xxhdpi | `./gradlew -Ppf.designcheck …testDebugUnitTest` |
| 4 | Пары «макет / приложение / отличия», SSIM, сортировка от худших | `python3 design-check/compare.py` |
| 5 | Разбор пар и отчёт | агент |

Снимки (шаг 3) — тесты `*DesignCheckTest` в `src/test` модулей фич с плагином `pf.screenshots`. Каждый
тест рисует содержимое экрана (`FeedContent`, `ProfileContent`, …) из готового состояния, без ViewModel,
через `DesignCheck.capture("<Артборд>", dark, heightDp, tab)` из `:core:screenshot-testing`. Имя снимка
совпадает с именем артборда — по нему находится пара. Системные панели — 24 dp сверху и снизу, как на
макетах (`LocalPfSystemBars`); у экранов вкладок рисуется нижняя панель, как в приложении.

Обычный прогон тестов (`testDebugUnitTest` в CI) эти тесты пропускает: им нужна среда Android целиком,
и рендер всех экранов долгий.

## Запуск вручную

```bash
# Container prerequisites: SDK, Maven mirror, Roboto, Python libs
scripts/agent-setup.sh --design-check

# App snapshots -> build/design-check/app/<Artboard>.png
ANDROID_HOME=/opt/android-sdk ./gradlew -Ppf.designcheck \
  :feature:operations:impl:testDebugUnitTest :feature:analytics:impl:testDebugUnitTest \
  :feature:profile:impl:testDebugUnitTest :feature:applock:impl:testDebugUnitTest

# Mockups for the same artboards -> build/design-check/mockups (canvas files already in build/design-check/site)
NODE_PATH="$(npm root -g)" node design-check/render.js build/design-check/site build/design-check/mockups

# Pairs and scores -> build/design-check/compare/<Artboard>.png, scores.json
python3 design-check/compare.py build/design-check/mockups build/design-check/app build/design-check/compare
```

## Как читать результат

- SSIM — только для сортировки: ниже 0.9 почти всегда есть заметные отличия, выше 0.95 — обычно мелочи.
- Расхождения в отчёте делятся на: вёрстка (отступы, размеры, порядок блоков), содержимое (тексты, набор
  строк и плиток), цвет и состояния. Для каждого — экран, что на макете, что в приложении, кто прав.
- Кто прав, решает человек: макет мог устареть, а приложение — следовать контракту API (`api.md`).

## Добавить экран

1. Разделить экран на обёртку с ViewModel и `…Content(state, обработчики)`.
2. В `src/test` модуля — `<Экран>DesignCheckTest` с состоянием по данным артборда (числа, тексты, даты как
   на макете) и вызовом `DesignCheck.capture` для светлого и тёмного артборда.
3. В `build.gradle.kts` модуля — `id("pf.screenshots")`.

## Routine «Design check на мерж в main»

Создаётся на claude.ai/code/routines:

- **Репозиторий:** `fin-assist/android`.
- **Триггер:** GitHub event → Pull request → `closed`, фильтры «Is merged = true», «Base branch = main».
- **Окружение:** Network access → Custom, «Also include default list of common package managers» и
  `dl.google.com` в Allowed domains (или Full). Setup script: `scripts/agent-setup.sh --design-check`.
- **Промпт:**

```text
Design check for fin-assist/android after a merge into main. Work in the cloned repository at main.

1. Run scripts/agent-setup.sh --design-check.
2. Run the design-check snapshots: ANDROID_HOME=/opt/android-sdk ./gradlew -Ppf.designcheck testDebugUnitTest
   (only *DesignCheckTest run in this mode). PNGs land in build/design-check/app/.
3. With the Artifact tool, read the published files of https://claude.ai/artifact/8ndjPbEdsxZKHPTxNWhePR into
   build/design-check/site/: every project/<Name>.dc.html that has a PNG in build/design-check/app/, plus
   project/canvas.json, project/ds/pf/tokens.css, project/ds/pf/components/bundle.css,
   project/ds/pf/components/bundle.js, and artifact-type/dc-runtime.js saved as support.js. Keep the
   project/ layout flattened (site/<Name>.dc.html, site/ds/...).
4. NODE_PATH="$(npm root -g)" node design-check/render.js build/design-check/site build/design-check/mockups
5. python3 design-check/compare.py build/design-check/mockups build/design-check/app build/design-check/compare
6. Look at every pair in build/design-check/compare/ (worst SSIM first) and write a report in Russian:
   per screen — what differs (layout, content, colour, states), where, mockup vs app, and a suggested
   fix with the file to change. Skip differences in data that the fixture does not control.
7. Push the comparison images and the report to the branch design-check/<merge commit short sha>
   (images under design-check/reports/<sha>/), and open a GitHub issue titled
   «Design check: <sha>» with the report and links to the images. Do not change application code.
```
