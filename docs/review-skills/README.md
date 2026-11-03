# Навыки PR-ревью (FIN-421)

Перед содержательным ревью выберите по diff минимальный достаточный набор, прочитайте файлы `SKILL.md`
из текущего коммита PR и выполните применимые процедуры. Название без чтения и применения не считается
использованием навыка. При изменении нескольких областей выберите навыки для каждой; весь каталог по умолчанию
читать не нужно. Обязательные проверки и полномочия заданы в корневом `AGENTS.md`.

| Навык | Применять при |
| --- | --- |
| [code-review](vendor/engineering-suite/code-review/SKILL.md) | Любой PR: проверка реализации относительно задачи и правил репозитория. |
| [codebase-design](vendor/engineering-suite/codebase-design/SKILL.md) | Изменения границ модулей, связности и публичных интерфейсов. |
| [domain-modeling](vendor/engineering-suite/domain-modeling/SKILL.md) | Изменения предметной модели и терминов. |
| [api-and-interface-design](vendor/engineering-suite/api-and-interface-design/SKILL.md) | REST, gRPC, DTO и публичные интерфейсы. |
| [security-and-hardening](vendor/engineering-suite/security-and-hardening/SKILL.md) | Доверенные границы, персональные данные, авторизация и ввод. |
| [ci-cd-and-automation](vendor/engineering-suite/ci-cd-and-automation/SKILL.md) | CI, сборка, выпуск и автоматизация. |
| [testing-setup](vendor/android-skills/testing-setup/SKILL.md) | Стратегия и инфраструктура тестирования Android. |
| [jetpack-compose-performance](vendor/android-skills/jetpack-compose-performance/SKILL.md) | Производительность Compose и рекомпозиции. |
| [android-intent-security](vendor/android-skills/android-intent-security/SKILL.md) | Безопасность Intent и компонентов. |
| [android-permissions-security](vendor/android-skills/android-permissions-security/SKILL.md) | Разрешения Android и IPC. |
| [anr-audit-and-fix](vendor/android-skills/anr-audit-and-fix/SKILL.md) | Риски ANR и блокировки главного потока. |

SQL и PostgreSQL навыки поставляются только в Go-бэкенд; в конкретном ревью они применяются лишь к
затронутым SQL/хранению/транзакциям. Навыки Android поставляются только в Android-репозиторий.
Для `contracts` применимы общие навыки и Go-навыки при изменении handwritten Go или генераторов.


В сводке укажите названия применённых навыков, если применяли их.

## Доступность и ограничения

Все материалы лежат в `docs/review-skills/vendor/` этого репозитория. Читайте их по относительным
путям из PR head; локальные установленные плагины владельца не требуются. Если файл недоступен,
продолжайте обязательные проходы из `AGENTS.md`. Не приписывайте навыку выполненные проверки,
если его инструкция не была прочитана. Навык не разрешает merge, публикацию, доступ к секретам или UI-тесты.


## Источники, лицензии и обновление

- `engineering-suite` 1.1.1, MIT: неизменённые файлы навыков из установленного пакета; [лицензия](licenses/MIT.txt).
- `android-skills` 1.0.19, Google LLC, Apache-2.0: [исходный проект](https://github.com/android/skills), [лицензия](licenses/Apache-2.0.txt).

Копии зафиксированы в Git. При обновлении сверяйте источник и лицензию, переносите `SKILL.md`
и используемые им файлы `references/`, проверяйте ссылки и обновляйте этот каталог одним PR.
Изменение Go-шаблона не обновляет другие репозитории автоматически. Проверка сценариев выбора —
[acceptance.md](acceptance.md).
