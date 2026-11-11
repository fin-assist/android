# Понятные финансы — Android

Клиент приложения «Понятные финансы»: импорт OFX-выписки Т-Банка, операции, аналитика, LLM-помощник.
Контракт API — репозиторий [fin-assist/contracts](https://github.com/fin-assist/contracts) (`docs/api.md`,
`openapi/openapi.yaml`); состав MVP и решения — Confluence PF (https://finassist.atlassian.net/wiki/spaces/PF);
макеты — Figma (https://www.figma.com/design/u8paWRq6KAT73UJ1x2jXLv, ссылка также в `design-check/README.md`).

## Стек

Kotlin 2.1, Jetpack Compose, Hilt (KSP), Navigation Compose (typed routes), OkHttp + Retrofit +
kotlinx.serialization, OkHttp SSE, DataStore, Tink (refresh-токен под Android Keystore).
Флаги — RuStore Remote Config. AppMetrica собирает продуктовые события в `stage` и `prod`, а также крэши, ANR и non-fatal в `mock`, `stage` и `prod` при заданном ключе.

## Структура

Монорепозиторий: каждая фича — модули `:api` (интерфейсы, модели, маршруты) и `:impl` (data, domain, ui).
Фичи зависят друг от друга только через `:api`; связывает интерфейсы с реализациями `:app`.
Правило проверяется при конфигурации Gradle и ломает сборку при нарушении — см. [docs/modules.md](docs/modules.md).
Реестр фичетогглов — [docs/flags.md](docs/flags.md).

## Сборка

Требования: Android Studio (Ladybug или новее), JDK 21 для Gradle (см. ниже), Android SDK 35.

```
./gradlew assembleMockDebug     # сборка без сервера и без ключей SDK
./gradlew assembleLocalDebug    # HTTP-клиент к bank-bff на машине разработчика (ниже, «Flavor local»)
./gradlew assembleStageDebug   # HTTPS-клиент к stage.plainfin.ru, отдельное приложение, ключи необязательны
./gradlew assembleProdDebug     # настоящий HTTP-клиент (stage 8: ключи SDK в sdk-keys.properties)
./gradlew test lintMockDebug    # то же, что в CI
```

### Flavor `mock`

Flavor `mock` содержит in-process фейковый бэкенд (`:mock:backend`),
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

Flavor-ы — одно измерение `env` (FIN-120): `mock`, `local`, `stage`, `prod`; измерения не перемножаются.
`stage` подключён к готовому серверному стенду (FIN-385, оставшийся пункт FIN-149).

### Flavor `local`

Настоящий HTTP-клиент против `bank-bff`, запущенного у разработчика. Ставится рядом с `prod` (`ru.finassist.pf.local`,
«Понятные финансы local»). Адрес — свойство Gradle `pf.apiBaseUrl` в `~/.gradle/gradle.properties` (или переменная
`PF_API_BASE_URL`); по умолчанию `http://10.0.2.2:8080/` — машина разработчика из эмулятора. С телефона —
адрес машины в локальной сети, например `pf.apiBaseUrl=http://192.168.1.20:8080/`. Неверный адрес (без схемы, с
логином, query) ломает конфигурацию с понятной причиной.

HTTP без TLS разрешён только в `localDebug` и только к хосту из `pf.apiBaseUrl` (сгенерированный
`network_security_config`); в `prod` и в любой release-сборке — запрещён. Флаги — локальные (код по умолчанию, свой
`app/src/local/assets/flags.json` по желанию, в git не попадает; docs/flags.md), события — в logcat, AppMetrica и RuStore
Remote Config выключены: локальные прогоны не попадают в продовые консоли.

### Flavor `stage`

Готовый HTTPS-стенд: **https://stage.plainfin.ru/**. Адрес зафиксирован в сборке; `pf.apiBaseUrl` меняет только
`local`. Приложение — `ru.finassist.pf.stage`, «Понятные финансы stage»: ставится рядом с mock/local/prod, имеет
отдельные данные и токены. Использует настоящий HTTP/SSE-клиент и те же SDK-провайдеры, что prod; mock-кода,
управляющих e2e-ссылок и закреплённой даты в нём нет. Cleartext запрещён в debug и release.

```bash
./gradlew :app:assembleStageDebug      # app/build/outputs/apk/stage/debug/app-stage-debug.apk
./gradlew :app:installStageDebug       # установить на подключённое устройство / эмулятор
```

В CI APK доступен как артефакт **app-stage-debug** в workflow CI. Release-вариант тоже проверяется, но CI не
подписывает его для распространения. UI-автотесты остаются на `mockE2e`: зелёный CI сборки не доказывает
сквозной прогон Android → публичный stage.

На серверном stage работают реальные Go-сервисы и fake-phone/fake-LLM. Для входа используй только отдельный
синтетический номер `+7000000xxxx` (четыре цифры), например `+70000003850`; реального звонка для fake-подтверждения
не требуется. Документы согласий на этом стенде — synthetic fixtures. Загружай синтетический OFX; затем проверь
прогресс, операции, аналитику и помощника. Для детерминированного сценария fake-LLM можно использовать вопрос
`#FIN362_TOOLS Покажи доходы и расходы по моим данным.`. Это проверка интеграции с фейковыми внешними провайдерами.

SDK выключены без stage-ключей; флаги тогда берутся из дефолтов `Flag`. Production-свойства не используются
как запасные. Если нужны отдельные stage-консоли, задай **их** ключи в `~/.gradle/gradle.properties` либо env:

| Свойство | Переменная окружения |
|---|---|
| `pf.stageRustoreRemoteConfigAppId` | `PF_STAGE_RUSTORE_REMOTE_CONFIG_APP_ID` |
| `pf.stageAppMetricaApiKey` | `PF_STAGE_APP_METRICA_API_KEY` |

Не подставляй ключи production-консолей в эти свойства. Stage использует собственное приложение AppMetrica.

TLS проверяется системными CA Android; отключать проверку сертификата не нужно. Опциональный pinning задаётся
**отдельно** парой `pf.stageApiPins` / `pf.stageApiPinsExpiration` (`PF_STAGE_API_PINS` /
`PF_STAGE_API_PINS_EXPIRATION`). Формат, минимум два ключа и срок — как у prod ниже; production-пины stage не наследует.
Реальные пины в git не кладутся. Пины stage пока не настроены: без них работает обычная CA-проверка.

### Закрепление сертификата в `prod`

Решение и процесс ротации — Confluence PF, «Архитектура» → «Вход трафика» → «Закрепление сертификата API (pinning)»
(FIN-121). Пока сертификата нет, пины не заданы и pinning выключен. Когда появится: в среде, где собирается релиз
(`~/.gradle/gradle.properties` или секреты CI), задать

```
pf.apiPins=sha256/<текущий ключ>,sha256/<резервный ключ>     # или PF_API_PINS
pf.apiPinsExpiration=2027-06-01                            # или PF_API_PINS_EXPIRATION
```

Пин — base64 SHA-256 от SPKI ключа: `openssl pkey -in key.pem -pubout -outform der | openssl dgst -sha256 -binary |
base64`. Нужно не меньше двух разных пинов и срок позже даты сборки, но не дальше года; иначе конфигурация падает с
причиной. Сборка `prod` получает `network_security_config` с `pin-set` для хоста `API_BASE_URL`; `local` и `mock` не
меняются. Реальные пины в репозиторий не кладутся.

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

Настоящий HTTP-клиент, флаги из RuStore Remote Config, аналитика и диагностика в AppMetrica.
Ключи SDK задаются в `~/.gradle/gradle.properties` или через переменные окружения сборки. При пустом ключе
AppMetrica не активируется. Без ключа RuStore используются значения по умолчанию из `Flag`.

| Свойство | Переменная окружения | Назначение |
|---|---|---|
| `pf.rustoreRemoteConfigAppId` | `PF_RUSTORE_REMOTE_CONFIG_APP_ID` | ID приложения в RuStore Remote Config |
| `pf.appMetricaApiKey` | `PF_APP_METRICA_API_KEY` | API-ключ SDK приложения AppMetrica для prod |
| `pf.stageAppMetricaApiKey` | `PF_STAGE_APP_METRICA_API_KEY` | API-ключ отдельного приложения для stage |
| `pf.mockAppMetricaApiKey` | `PF_MOCK_APP_METRICA_API_KEY` | API-ключ отдельного приложения для mock-альфы |

В `mock` продуктовые события пишутся в logcat, автоматические сессии AppMetrica отключены. В `local` и
`mockE2e` SDK отсутствует. Из крэшей и non-fatal передаются тип, стек и безопасный код ошибки. Для диагностики
приложение добавляет `rc_ver`, последние шаги сессии и непрозрачный `user_id`, который очищается при выходе.
SDK собирает базовые технические данные об устройстве и установке. Геолокация устройства, рекламные идентификаторы,
автосбор покупок и ссылки запуска отключены.

Для релизной сборки с ключом Gradle-плагин готовит `app/build/appmetrica/<variant>/result/mapping.zip`. Загрузи
архив в соответствующее приложение AppMetrica через **Настройки → Крэши → Android**. Автоматическая загрузка
потребовала бы отдельного Post API key. [Инструкция AppMetrica](https://appmetrica.yandex.ru/docs/ru/sdk/android/analytics/android-gradle-plugin)

Перед выпуском проверь в отдельном приложении AppMetrica доставку одного события, non-fatal, крэша и ANR, а также
наличие `rc_ver` и отсутствие `user_id` в новых отчётах после выхода.

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

## Автоматизация обновлений зависимостей

[Политика Renovate](docs/renovate-policy.md) (FIN-211): одна актуальная release/main, автомерж полностью ботских PR после обязательного CI, один повтор красного CI и закрытие с явным повтором через Dependency Dashboard. AndroidX/Compose остаются на ручной проверке. Исходные группировки, ограничения версий и расписания — `renovate-base.json`; generated target и quarantine — ветка `renovate-policy`. Активация требует отдельного токена и строгой защиты веток; по умолчанию выключена.

FIN-456: код контроллера и его регрессии подключены из immutable `fin-assist/go-ci`; локальные config/runtime lock и состояния сохраняются. Команды pinned source и bootstrap с явным consumer root — в [политике Renovate](docs/renovate-policy.md).

## Профильное ревью (FIN-421)

Перед ревью применяются [профильные навыки](docs/review-skills/README.md) из проверяемой ревизии.
Обязательные требования, выбор и ограничения — в `AGENTS.md`, раздел `Code Review Rules`.
