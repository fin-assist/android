# Сверка Qase и UI-тестов — FIN-450

Сверены все 90 кейсов из mapping на baseline `967f4559bd6cf4db568ca760105828bbc4bb0a9a`: предусловия, действия, отдельные ожидаемые результаты и постусловия. Исходный текст сохранён в `source` каждого кейса. Q53, Q60, Q90 и Q94 не входят в эти 90; работа над Q60 остановлена владельцем.

Согласованные требования и актуальные решения определяют ожидаемое поведение. Qase задаёт сценарий, код показывает реализацию, тест показывает фактически проверяемые условия. Исправление устаревшего Qase требует подтверждения из требований, контракта или Figma; текущий код сам по себе таким подтверждением не служит.

`covered` — найдено существующее доказательство; `fixed` — добавлено различающее утверждение; `bounded` — явно указанная граница UI/JVM/mock; `gap` — открытое расхождение. Это статусы анализа, а не результаты исполнения. Полные исходные условия, строки доказательств и ограничения находятся в JSON группы.

После merge PR280 выполнен полный автоматический прогон на a9aeb67: [Qase95](https://app.qase.io/run/PLAINFIN/dashboard/95), 86/90 Passed и4Failed. Все13native прошли; из79Maestro упали Q15/Q23/Q36/Q41. Факты и подготовленные исправления — [postmerge.json](postmerge.json). Исправленные четыре flow затем прошли точечно на API34: Q15/Q36 — [Qase96](https://app.qase.io/run/PLAINFIN/dashboard/96), Q23/Q41 после первоначальных сбоев подготовки — [Qase97](https://app.qase.io/run/PLAINFIN/dashboard/97). Повтор выполнен без изменений кода/APK/таймаутов, первые падения сохранены; [фактические попытки](postmerge-local.json). Полный набор повторно не запускался; исходный API35 Q77–79 отдельно подтверждён в execution.json.

После merge PR283 выполнен новый полный автоматический [Qase98](https://app.qase.io/run/PLAINFIN/dashboard/98): 89/90 Passed, все79Maestro Passed, native12/13Passed. Q15/Q23/Q36/Q41 прошли в полном наборе. Единственный Failed — native-компонент Q17: закрытие выбора темы опережает асинхронное появление нового label в профиле. Добавлена явная прокрутка стабильной строки перед ожиданием label в прежнем лимите10с; проверка выбранного текста и независимые pixel oracle сохранены. После первых неудачных локальных попыток ([Qase99](https://app.qase.io/run/PLAINFIN/dashboard/99)) обе части Q17 прошли на ae792e93 ([Qase100](https://app.qase.io/run/PLAINFIN/dashboard/100)), API34. Все попытки — [q17-local.json](q17-local.json); исходный15-минутный лимит соблюдён, эмулятор остановлен, полный набор повторно не запускался. Фактический результат — [postmerge-283.json](postmerge-283.json); прежние результаты95/96/97 сохранены.

В матрице: 397 шагов, 1020 отдельных требований. Структурная проверка гарантирует комплектность исходных шагов и существование ссылок, но не доказывает правильность сценария.

| Qase | Сценарий | Требования covered / fixed / bounded / gap | Подробная сверка |
|---|---|---|---|
| [Q1](https://app.qase.io/case/PLAINFIN-1) | Вход существующего пользователя и вкладки | 9 / 1 / 1 / 0 | [shell](shell.json#L6) |
| [Q2](https://app.qase.io/case/PLAINFIN-2) | Регистрация нового номера до первой выписки | 12 / 1 / 0 / 0 | [shell](shell.json#L339) |
| [Q3](https://app.qase.io/case/PLAINFIN-3) | Проверка номера и «Изменить номер» на экране звонка | 10 / 2 / 1 / 0 | [shell](shell.json#L691) |
| [Q4](https://app.qase.io/case/PLAINFIN-4) | Регистрация закрыта флагом auth.registration | 6 / 1 / 1 / 0 | [shell](shell.json#L1071) |
| [Q5](https://app.qase.io/case/PLAINFIN-5) | Разблокировка после перезапуска, неверный код | 6 / 1 / 0 / 0 | [shell](shell.json#L1312) |
| [Q6](https://app.qase.io/case/PLAINFIN-6) | «Забыли код?» — выход и новый вход | 7 / 0 / 0 / 0 | [shell](shell.json#L1529) |
| [Q7](https://app.qase.io/case/PLAINFIN-7) | Смена код-пароля в профиле | 7 / 2 / 0 / 0 | [shell](shell.json#L1737) |
| [Q8](https://app.qase.io/case/PLAINFIN-8) | Лента, карточка операции, смена категории | 4 / 2 / 1 / 0 | [finance](finance.json#L6) |
| [Q9](https://app.qase.io/case/PLAINFIN-9) | Поиск операций и фильтры | 6 / 8 / 0 / 0 | [finance](finance.json#L364) |
| [Q10](https://app.qase.io/case/PLAINFIN-10) | Аналитика — блоки, независимый выбор периодов, переводы, переход к операциям | 10 / 5 / 0 / 0 | [finance](finance.json#L1046) |
| [Q11](https://app.qase.io/case/PLAINFIN-11) | Блоки аналитики и помощник выключены флагами | 7 / 0 / 0 / 0 | [finance](finance.json#L2588) |
| [Q12](https://app.qase.io/case/PLAINFIN-12) | Помощник — согласие, вопрос, ответ, переход к операциям | 13 / 7 / 0 / 0 | [assistant](assistant.json#L6) |
| [Q13](https://app.qase.io/case/PLAINFIN-13) | Помощник — непонятный вопрос не тратит попытку, лимит 5 в день | 10 / 3 / 0 / 0 | [assistant](assistant.json#L495) |
| [Q14](https://app.qase.io/case/PLAINFIN-14) | Загрузка новых операций, повтор той же выписки, удаление загрузки | 19 / 6 / 1 / 0 | [statements](statements.json#L6) |
| [Q15](https://app.qase.io/case/PLAINFIN-15) | Загрузка — CSV, не выписка, другой банк | 5 / 2 / 0 / 0 | [statements](statements.json#L1020) |
| [Q16](https://app.qase.io/case/PLAINFIN-16) | Вход в банк и выбор выписки системным диалогом | 3 / 2 / 1 / 0 | [statements](statements.json#L1317) |
| [Q17](https://app.qase.io/case/PLAINFIN-17) | Профиль — тема и выход из аккаунта | 5 / 2 / 0 / 0 | [shell](shell.json#L2034) |
| [Q18](https://app.qase.io/case/PLAINFIN-18) | Удаление аккаунта — код-пароль, подтверждение в диалоге, «Назад» во время удаления | 7 / 3 / 0 / 0 | [shell](shell.json#L2292) |
| [Q19](https://app.qase.io/case/PLAINFIN-19) | Ошибка соединения — подсказка и «Повторить» | 5 / 4 / 0 / 0 | [shell](shell.json#L2633) |
| [Q20](https://app.qase.io/case/PLAINFIN-20) | Помощник — нет сети вместо поля ввода и «Повторить» | 15 / 3 / 0 / 0 | [assistant](assistant.json#L797) |
| [Q21](https://app.qase.io/case/PLAINFIN-21) | Загрузка — «Не скачивается?», нет сети при отправке файла и «Повторить» | 4 / 5 / 0 / 0 | [statements](statements.json#L1572) |
| [Q22](https://app.qase.io/case/PLAINFIN-22) | Загрузка — ни одной прочитанной операции, непрочитанные строки видны и открываются списком | 6 / 6 / 0 / 0 | [statements](statements.json#L1946) |
| [Q23](https://app.qase.io/case/PLAINFIN-23) | Загрузка — операция без описания принимается и видна в ленте как «Без описания» | 6 / 3 / 0 / 0 | [statements](statements.json#L2358) |
| [Q24](https://app.qase.io/case/PLAINFIN-24) | Помощник — чип ответа после удаления всех выписок | 7 / 0 / 0 / 0 | [assistant](assistant.json#L1278) |
| [Q25](https://app.qase.io/case/PLAINFIN-25) | Помощник — «Повторить» после сбоя модели сохраняет режим «Без переводов» | 6 / 3 / 0 / 0 | [assistant](assistant.json#L1491) |
| [Q26](https://app.qase.io/case/PLAINFIN-26) | Помощник — ответ не получился, двойное нажатие «Повторить» даёт один ответ без ошибки | 9 / 2 / 0 / 0 | [assistant](assistant.json#L1750) |
| [Q27](https://app.qase.io/case/PLAINFIN-27) | Лента группирует операции по дням в поясе профиля | 5 / 0 / 1 / 0 | [finance](finance.json#L2875) |
| [Q28](https://app.qase.io/case/PLAINFIN-28) | Загрузка — исправленный файл дочитывает непрочитанные строки прошлой загрузки | 6 / 3 / 0 / 0 | [statements](statements.json#L2677) |
| [Q29](https://app.qase.io/case/PLAINFIN-29) | Поиск — фильтр с неизвестным kind не теряется, а показывается чипом «Как на „Аналитике“» | 2 / 3 / 0 / 0 | [finance](finance.json#L3076) |
| [Q30](https://app.qase.io/case/PLAINFIN-30) | Аналитика — блок, который не удалось посчитать, не ломает экран | 4 / 0 / 0 / 0 | [finance](finance.json#L3422) |
| [Q31](https://app.qase.io/case/PLAINFIN-31) | Аналитика — блок неизвестного типа и неизвестная блокировка показываются текстом сервера | 1 / 2 / 0 / 0 | [finance](finance.json#L3638) |
| [Q32](https://app.qase.io/case/PLAINFIN-32) | Загрузка — серверные тексты для неизвестного уведомления и неизвестной закрытой функции | 7 / 1 / 0 / 0 | [statements](statements.json#L2992) |
| [Q33](https://app.qase.io/case/PLAINFIN-33) | Версия ниже минимальной — экран обновления поверх приложения | 5 / 2 / 0 / 0 | [shell](shell.json#L2950) |
| [Q34](https://app.qase.io/case/PLAINFIN-34) | Техработы — ожидание Retry-After, одна автопроверка и ручное восстановление | 6 / 4 / 0 / 0 | [shell](shell.json#L3144) |
| [Q35](https://app.qase.io/case/PLAINFIN-35) | Техработы до входа — экран техработ поверх входа, без времени окончания | 4 / 1 / 1 / 0 | [shell](shell.json#L3469) |
| [Q36](https://app.qase.io/case/PLAINFIN-36) | Аналитика — карточки наблюдений открывают свои операции | 2 / 5 / 0 / 0 | [finance](finance.json#L3830) |
| [Q37](https://app.qase.io/case/PLAINFIN-37) | Устаревшая выписка: плашка на «Операциях» и «Аналитике» с датой конца выписки | 7 / 2 / 1 / 0 | [finance](finance.json#L4251) |
| [Q38](https://app.qase.io/case/PLAINFIN-38) | Аналитика — Поиск «как на Аналитике» не показывает переводы себе | 4 / 3 / 0 / 0 | [finance](finance.json#L4656) |
| [Q39](https://app.qase.io/case/PLAINFIN-39) | Помощник — вопрос не по теме даёт текст сервера без «Повторить» и не тратит попытку | 9 / 0 / 0 / 0 | [assistant](assistant.json#L2028) |
| [Q40](https://app.qase.io/case/PLAINFIN-40) | Помощник — «с тех пор данные менялись» под прежним ответом | 6 / 2 / 0 / 0 | [assistant](assistant.json#L2240) |
| [Q41](https://app.qase.io/case/PLAINFIN-41) | Время на звонок вышло и «Позвонить ещё раз» | 6 / 3 / 1 / 0 | [shell](shell.json#L3628) |
| [Q42](https://app.qase.io/case/PLAINFIN-42) | Помощник — «не нашли» за покрытый период — ответ, попытка списывается | 8 / 0 / 0 / 0 | [assistant](assistant.json#L2490) |
| [Q43](https://app.qase.io/case/PLAINFIN-43) | Обрыв потока без терминального события — поток переоткрывается, ошибки нет | 10 / 1 / 1 / 0 | [shell](shell.json#L3987) |
| [Q44](https://app.qase.io/case/PLAINFIN-44) | Звонок — 429 после обрыва потока | 7 / 2 / 1 / 0 | [shell](shell.json#L4364) |
| [Q45](https://app.qase.io/case/PLAINFIN-45) | Загрузка — 429 сохраняет исходную паузу при возврате на экран | 5 / 2 / 0 / 0 | [statements](statements.json#L3249) |
| [Q46](https://app.qase.io/case/PLAINFIN-46) | Техработы во время разбора выписки — переоткрытие потока получает 503, экран техработ поверх загрузки, после техработ выписка разобрана | 6 / 5 / 0 / 0 | [shell](shell.json#L4724) |
| [Q47](https://app.qase.io/case/PLAINFIN-47) | История загрузок: период выписки, даты операций и отсутствие периода (FIN-198) | 7 / 3 / 0 / 0 | [statements](statements.json#L3534) |
| [Q48](https://app.qase.io/case/PLAINFIN-48) | Универсальные серверные сообщения вне помощника | 8 / 2 / 0 / 0 | [shell](shell.json#L5079) |
| [Q49](https://app.qase.io/case/PLAINFIN-49) | OFF_TOPIC: недельная блокировка сохраняет черновик и дневной остаток | 10 / 6 / 0 / 0 | [assistant](assistant.json#L2687) |
| [Q50](https://app.qase.io/case/PLAINFIN-50) | REST retryable=false отделён от новой попытки генерации | 8 / 1 / 0 / 0 | [shell](shell.json#L5432) |
| [Q51](https://app.qase.io/case/PLAINFIN-51) | Загрузка — неизвестный возраст ключа требует явного нового действия | 4 / 3 / 0 / 0 | [statements](statements.json#L3894) |
| [Q52](https://app.qase.io/case/PLAINFIN-52) | Помощник — RetryAnswer возобновляется после исправления согласия | 8 / 2 / 0 / 0 | [assistant](assistant.json#L3138) |
| [Q54](https://app.qase.io/case/PLAINFIN-54) | Вход — смена документа на Start и закреплённая версия согласия в Register | 17 / 0 / 1 / 0 | [shell](shell.json#L5747) |
| [Q55](https://app.qase.io/case/PLAINFIN-55) | Помощник — обновление документа согласия без повтора старого PUT | 18 / 3 / 0 / 0 | [assistant](assistant.json#L3429) |
| [Q56](https://app.qase.io/case/PLAINFIN-56) | Непрочитанные строки: две операции одной физической строки с одинаковой причиной | 6 / 2 / 0 / 0 | [statements](statements.json#L4185) |
| [Q57](https://app.qase.io/case/PLAINFIN-57) | Два независимых подтверждения до отправки телефона | 7 / 2 / 0 / 0 | [shell](shell.json#L6290) |
| [Q58](https://app.qase.io/case/PLAINFIN-58) | Согласие до телефона — восстановление без сети | 5 / 3 / 0 / 0 | [shell](shell.json#L6602) |
| [Q59](https://app.qase.io/case/PLAINFIN-59) | Новый вход после запрета повтора | 4 / 4 / 0 / 0 | [shell](shell.json#L6906) |
| [Q61](https://app.qase.io/case/PLAINFIN-61) | Помощник — вкладка сохраняет черновик и историю, согласие повторно открывается без автоотправки | 11 / 3 / 0 / 0 | [assistant](assistant.json#L3939) |
| [Q62](https://app.qase.io/case/PLAINFIN-62) | Ошибка связи — VPN-подсказка меняется после повтора | 3 / 3 / 0 / 0 | [shell](shell.json#L7167) |
| [Q63](https://app.qase.io/case/PLAINFIN-63) | Аналитика после импорта — подготовка, ограниченное ожидание и явный повтор | 11 / 3 / 0 / 0 | [finance](finance.json#L4983) |
| [Q64](https://app.qase.io/case/PLAINFIN-64) | Согласие помощника — измеренный отступ 24 dp после прокрутки при шрифте 150% | 3 / 2 / 1 / 0 | [assistant](assistant.json#L4364) |
| [Q65](https://app.qase.io/case/PLAINFIN-65) | Согласие помощника из Профиля разблокирует сохранённый черновик вкладки | 6 / 4 / 0 / 0 | [assistant](assistant.json#L4556) |
| [Q66](https://app.qase.io/case/PLAINFIN-66) | Профиль — ошибка сохранения темы сменяется ошибкой чтения и доступным повтором | 4 / 1 / 0 / 0 | [shell](shell.json#L7405) |
| [Q67](https://app.qase.io/case/PLAINFIN-67) | История загрузок — ошибка удаления сменяется ошибкой чтения и доступным повтором | 4 / 1 / 0 / 0 | [shell](shell.json#L7600) |
| [Q68](https://app.qase.io/case/PLAINFIN-68) | Позднее включение помощника допускает вход и сохраняется до конца слоя | 7 / 3 / 0 / 0 | [assistant](assistant.json#L4855) |
| [Q69](https://app.qase.io/case/PLAINFIN-69) | Диктовка — готовность, отмена и редактируемый черновик (симуляция распознавателя) | 18 / 5 / 3 / 0 | [assistant](assistant.json#L5189) |
| [Q70](https://app.qase.io/case/PLAINFIN-70) | Обновление свайпом сохраняет данные, период, фильтры и черновик (FIN-399) | 24 / 4 / 0 / 0 | [finance](finance.json#L5593) |
| [Q71](https://app.qase.io/case/PLAINFIN-71) | Аналитика — фактические расходы, прогноз и открываемое пояснение полной истории | 16 / 2 / 0 / 0 | [finance](finance.json#L7086) |
| [Q72](https://app.qase.io/case/PLAINFIN-72) | FIN-404: отказ в разрешении микрофона сохраняет черновик без отправки вопроса | 5 / 4 / 0 / 0 | [assistant](assistant.json#L5952) |
| [Q73](https://app.qase.io/case/PLAINFIN-73) | Аналитика — повтор смены периода и независимая ошибка платежа | 12 / 4 / 1 / 0 | [finance](finance.json#L8327) |
| [Q74](https://app.qase.io/case/PLAINFIN-74) | Аналитика — выбор месяца столбиком, нулевые расходы и повтор ошибки | 14 / 3 / 0 / 0 | [finance](finance.json#L9249) |
| [Q75](https://app.qase.io/case/PLAINFIN-75) | Аналитика «За всё время» — периоды, переводы и обновление данных (FIN-400) | 7 / 8 / 0 / 0 | [finance](finance.json#L10239) |
| [Q76](https://app.qase.io/case/PLAINFIN-76) | Скользящие 12 и 3 месяца: точные даты, вкладки и переход в календарный месяц | 6 / 11 / 2 / 0 | [finance](finance.json#L11618) |
| [Q77](https://app.qase.io/case/PLAINFIN-77) | Неверный код целиком виден на малом экране при разблокировке и удалении | 6 / 1 / 0 / 0 | [shell](shell.json#L7795) |
| [Q78](https://app.qase.io/case/PLAINFIN-78) | Точки и действия PIN доступны без ошибки при крупном шрифте во всей семье экранов | 6 / 1 / 0 / 0 | [shell](shell.json#L8104) |
| [Q79](https://app.qase.io/case/PLAINFIN-79) | Наблюдатель PIN-прокрутки переживает отмену во время удерживаемого UserInput drag | 8 / 0 / 1 / 0 | [shell](shell.json#L8373) |
| [Q80](https://app.qase.io/case/PLAINFIN-80) | Аналитика: скользящие периоды в своих вкладках и скрытая навигация за всё время | 8 / 7 / 0 / 0 | [finance](finance.json#L13118) |
| [Q81](https://app.qase.io/case/PLAINFIN-81) | Не подписка: обновлённый список, сохранение при пересчёте и явный повтор неизвестного результата | 21 / 8 / 1 / 0 | [finance](finance.json#L13833) |
| [Q82](https://app.qase.io/case/PLAINFIN-82) | Аналитика: сохранение готовых показателей во время пересчёта | 19 / 4 / 0 / 0 | [finance](finance.json#L15720) |
| [Q83](https://app.qase.io/case/PLAINFIN-83) | График аналитики: вся история, соседние страницы и возврат к выбранному месяцу | 12 / 0 / 1 / 0 | [finance](finance.json#L17020) |
| [Q84](https://app.qase.io/case/PLAINFIN-84) | Выписка — выбор включительного периода и ошибки подготовки ссылки (FIN-416) | 6 / 2 / 0 / 0 | [statements](statements.json#L4487) |
| [Q85](https://app.qase.io/case/PLAINFIN-85) | Выписка — браузер получает точную серверную ссылку выбранного периода (FIN-416) | 4 / 1 / 0 / 0 | [statements](statements.json#L4893) |
| [Q86](https://app.qase.io/case/PLAINFIN-86) | Операции — неподдерживаемый пояс сохраняет проверенный кэш и восстанавливается явно (FIN-416) | 0 / 5 / 1 / 0 | [finance](finance.json#L17668) |
| [Q87](https://app.qase.io/case/PLAINFIN-87) | Загрузка — неподдерживаемый пояс условий и результата (FIN-416) | 6 / 2 / 0 / 0 | [statements](statements.json#L5085) |
| [Q88](https://app.qase.io/case/PLAINFIN-88) | Аналитика: неподдерживаемый пояс и новый вход не подменяют готовый снимок | 4 / 5 / 3 / 0 | [finance](finance.json#L18090) |
| [Q89](https://app.qase.io/case/PLAINFIN-89) | Неизвестный результат «Не подписка»: явное чтение без подтверждения команды | 11 / 13 / 0 / 0 | [finance](finance.json#L18830) |
| [Q91](https://app.qase.io/case/PLAINFIN-91) | Не подписка — ошибка после сохранения и явный повтор обновляют полный список | 16 / 7 / 2 / 0 | [finance](finance.json#L20636) |
| [Q92](https://app.qase.io/case/PLAINFIN-92) | Возврат денег — распознавание, ручная категория и расходы вместо дохода | 11 / 0 / 1 / 0 | [finance](finance.json#L21697) |
| [Q93](https://app.qase.io/case/PLAINFIN-93) | Смена категории разделяет пару собственных переводов на две операции | 21 / 0 / 1 / 0 | [finance](finance.json#L22356) |

Опубликованы и подтверждены обратным чтением уточнения 32 существующих кейсов Qase; точные изменения — в [publication.json](publication.json). ID, статусы, автоматизация и незатронутые поля сохранены. После отдельного разрешения владельца созданы фактические результаты точечных прогонов; прежние результаты не изменялись.

Q49 уточнён решением владельца от 06.11.2026: черновик сохраняется при отказе до закрытия вложенного чата; после полного Back и повторного открытия прежний текст не восстанавливается. История и дневной остаток сохраняются. Решение записано в [Confluence](https://finassist.atlassian.net/wiki/spaces/PF/pages/11370516), версия 4, и Qase49; тест проверяет оба момента отдельно. Производственное хранение не менялось. Q77–79 подтверждены на отдельном API35; ранние незавершённые попытки сохранены в execution.json и истории Qase.

Пройдены сборка mockE2e и native test APK, `checkNoMockCode`, 569 JVM-тестов backend, 15 JVM-тестов control helpers, 23 Python-теста Qase reporter, синтаксис YAML и проверка комплектности матрицы. Kover Unit: 139/139 изменённых исполняемых строк, учитываемых Unit-отчётом. MockE2e helpers исключены из стандартного Unit variant и отдельно проверены JVM-тестами. Это не заменяет запуск новых UI/native-проверок.

Само наличие mapping или зелёного старого прогона не означает точного совпадения всех требований.

Q17 и Q84 принимаются целиком только при наличии Maestro- и native-частей в отчёте. Частичный успешный прогон отмечается Skipped с недостающим тестом; наблюдаемое падение остаётся Failed. Штатные Python-проверки: 417/417. Исторические результаты Qase не изменялись; падения первых попыток сохранены.

Локальные прогоны выявили и исправили пять проблем самих тестов: повторный Back после уже закрытой IME в Q49; невидимую после смены темы строку в Q17 (явная прокрутка в Maestro/native); слишком короткое ожидание начальной готовности legal document в native Q84 (60с, как у palette companion). Ожидание native Q84 допускает краткое отсутствие Compose root при переключении Activity, сохраняя обязательную квитанцию конфигурации. Для выделения всех восьми дней Q84 используется фактическое Android checked semantics вместо selected. Ожидаемые результаты, pixel oracle, проверки отсутствия браузера и таймауты бизнес-действий сохранены.

Локальный итог: все 8 согласованных компонентов для 6 кейсов прошли. Q49 — [run89](https://app.qase.io/run/PLAINFIN/dashboard/89); Q17 (обе части) — [run91](https://app.qase.io/run/PLAINFIN/dashboard/91); Q77 — [run92](https://app.qase.io/run/PLAINFIN/dashboard/92); Q84 (обе части) — [run93](https://app.qase.io/run/PLAINFIN/dashboard/93); Q78/Q79 — [run94](https://app.qase.io/run/PLAINFIN/dashboard/94). После исходного окна в 45 минут владелец отдельно разрешил до 15 минут только на Q78/Q79; оба теста завершены в новом лимите. Оба эмулятора остановлены. Полный набор позднее автоматически выполнен после merge; результат86/90Passed, исправления четырёх падений прошли отдельную точечную UI-проверку (postmerge-local.json).

Постмержевые исправления Q15/Q23/Q36/Q41 подготовлены по исходным артефактам CI и каноническим правилам, сохраняя даты/числа/категорию/сроки. Поля source и существующие кейсы Qase не изменены. Разрешённая точечная проверка завершена в пределах 25 минут; выделенный эмулятор остановлен. Полный постмержевый прогон исправлений состоялся после merge PR283: Qase98,89/90Passed; оставшееся падение native Q17 описано выше.
