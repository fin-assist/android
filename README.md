# Понятные финансы — Android

Клиент приложения «Понятные финансы»: импорт OFX-выписки Т-Банка, операции, аналитика, LLM-помощник.
Контракт API, состав MVP и макеты — в проекте «Финансовый консультант» (`api.md`, `openapi.yaml`,
`mvp-scope.md`); макеты экранов — в Figma (ссылка в `design-check/README.md`).

## Стек

Kotlin 2.1, Jetpack Compose, Hilt (KSP), Navigation Compose (typed routes), OkHttp + Retrofit +
kotlinx.serialization, OkHttp SSE, DataStore, Tink (refresh-токен под Android Keystore).
Флаги — RuStore Remote Config, события — MyTracker (flavor `prod`); крэши и ANR — Tracer (оба flavor, при заданном токене).

## Структура

Монорепозиторий: каждая фича — модули `:api` (интерфейсы, модели, маршруты) и `:impl` (data, domain, ui).
Фичи зависят друг от друга только через `:api`; связывает интерфейсы с реализациями `:app`.
Правило проверяется при конфигурации Gradle и ломает сборку при нарушении — см. [docs/modules.md](docs/modules.md).
Реестр фичетогглов — [docs/flags.md](docs/flags.md).

## Сборка

Требования: Android Studio (Ladybug или новее), JDK 21 для Gradle (см. ниже), Android SDK 35.

```
./gradlew assembleMockDebug     # сборка без сервера и без ключей SDK
./gradlew assembleProdDebug     # настоящий HTTP-клиент (stage 8: ключи SDK в sdk-keys.properties)
./gradlew test lintMockDebug    # то же, что в CI
```

### Flavor `mock`

Серверной части пока нет, поэтому flavor `mock` содержит in-process фейковый бэкенд (`:mock:backend`),
который реализует тот же контракт `:core:api` на данных реальной выписки: разбор OFX, дедупликация,
пары переводов между своими счетами, возвраты, помесячные агрегаты, пороги полноты данных, потоки
прогресса и ответа помощника, лимит 5 вопросов в сутки, идемпотентность.

Данные берутся из **вашей** выписки, которая в git не попадает:

```
app/src/mock/assets/private/statement.ofx     # ваш файл, каталог в .gitignore
mock/backend/src/main/assets/statements/fixture.ofx   # обезличенная фикстура для CI и для тех, у кого нет выписки
```

Если приватного файла нет, мок берёт фикстуру. Экран загрузки в `mock` работает по-настоящему: можно
выбрать любой OFX через системный пикер, он будет разобран на устройстве.

Вход в `mock`: единственный «существующий» номер — из `mock/backend` (`MockConfig.existingPhone`);
любой другой номер — новый пользователь (регистрация, если флаг `auth.registration` включён).

### JDK для Gradle

Gradle 8.11 не запускается на JDK 25, а Android Studio 2026.x по умолчанию берёт встроенный JBR 25 —
сборка падает с единственной строкой `25.0.3`. Нужен JDK 21 (как в CI): `brew install --cask temurin@21`,
затем Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK → 21.
Файл `gradle/gradle-daemon-jvm.properties` фиксирует JDK 21 для демона Gradle и при запуске из консоли.
Байткод при этом остаётся Java 17 — это предел для Android с AGP 8.x. Переход на Gradle 9 / AGP 9
(сборка на встроенном JBR 25) — отдельная задача.

### Сборка в контейнере агента

В чистом Linux-контейнере (облачная сессия агента) `scripts/agent-setup.sh` ставит Android SDK под версии из
`gradle/libs.versions.toml`, пишет `local.properties` и подключает зеркало Maven Central (облачные адреса
получают 429 от `repo.maven.apache.org`). Повторный запуск ничего не переустанавливает.

### Flavor `prod`

Настоящий HTTP-клиент, флаги из RuStore Remote Config, события в MyTracker.
Ключи SDK не хранятся в репозитории: Gradle-свойства в `~/.gradle/gradle.properties` или переменные
окружения в CI. Без ключа соответствующий SDK не включается (флаги — значения по умолчанию из `Flag`).

| Свойство | Переменная окружения | Что это |
|---|---|---|
| `pf.rustoreRemoteConfigAppId` | `PF_RUSTORE_REMOTE_CONFIG_APP_ID` | ID приложения в RuStore Remote Config |
| `pf.mytrackerSdkKey` | `PF_MYTRACKER_SDK_KEY` | SDK key MyTracker — выдаётся только опубликованному приложению; до публикации пусто |

Пока ключ MyTracker пуст, события пишутся никуда.

### Крэши и ANR: Tracer (оба flavor)

Tracer (VK, консоль RuStore) собирает крэши, ANR и non-fatal ошибки. Токены — из консоли Tracer, в репозиторий не
коммитятся:

| Свойство | Переменная окружения | Что это |
|---|---|---|
| `pf.tracerAppToken` | `PF_TRACER_APP_TOKEN` | Токен приложения. Без него SDK не стартует ни в одном варианте: провайдер запуска выключен в манифесте, `TracerCrashReporter` ничего не делает. CI проверяет это по собранному манифесту (`scripts/check-tracer-off.py`) |
| `pf.tracerPluginToken` | `PF_TRACER_PLUGIN_TOKEN` | Токен Gradle-плагина: загрузка маппингов R8 у минифицированных сборок. Без него маппинги не грузятся |

Текст исключений в Tracer не уходит — ни у non-fatal, ни у фатальных крашей (`ScrubbedThrowable`): только тип, стек и код ошибки; в debug-сборке исходный текст фатального краша остаётся в logcat (`PfCrash`). С токеном отчёты отправляются и из debug-сборок (`setDebugUpload`): токен задают только для сборки, чьи крэши
нужны, — сейчас это `mock` альфы. В `mock` отчёты дублируются в logcat (`PfTracking`). Как проверить: собрать с
токеном, вызвать краш, ANR (заблокировать главный поток на 5+ секунд) и non-fatal — они появятся в консоли с
`userId` и ключом `rc_ver`; после выхода из аккаунта — без `userId`.

Ключи флагов в консоли RuStore — как в `docs/flags.md` (булевы значения). Таргетинг и AB — по `account`
(наш `user_id` после входа).

## UI-автотесты

Сценарии пользователя — на Maestro (`.maestro/`), против сборки `mockE2e` с закреплённой датой и тестовыми
хуками; то, что Maestro не умеет (системный выбор файла), — нативными тестами в `app/src/androidTestMock/`.
Запуск на эмуляторе: `scripts/e2e.sh` (быстрый прогон — `--tags smoke`). В CI — только после мержа в `main`.
Подробности, теги и покрытие — [docs/e2e.md](docs/e2e.md).

## Документирование

Комментарии — там, где логика неочевидна (правила учёта, идемпотентность, анимация), а не на каждом
методе. Публичные интерфейсы в `:api` документируются KDoc. Решения по архитектуре — в `docs/`.
