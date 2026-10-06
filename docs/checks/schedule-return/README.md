# Диагностика возврата из расписания — 2026-10-06

Исходная рабочая копия: HEAD `be6a9db`, отслеживаемые файлы без изменений. Ранее созданные неотслеживаемые логи QA-001 не изменялись. Диагностика выполнена на отдельном временном API 36 AVD `ScheduleReturn`, `emulator-5558`, Europe/Moscow, с повторяемыми примерами R05. Пользовательские устройства не изменялись.

## Установлено

- Исходный `AppNavigationTest` воспроизвёл таймаут: 7 случаев, 1 failure, 59.556 s. Упал `unscheduledCourseCanCancelEnableAndEditThroughPencil` на строке 237; semantics показывала `screen_schedule` и disabled `schedule_save`. [Вывод](baseline-navigation.txt).
- В отдельном успешном измеренном сценарии запись занимала 10–62 ms, а весь этап до окончания сверки — 1.1–4.0 s. [Измерения](diagnostic-timing.txt), [сценарий 1/1](diagnostic-navigation.txt).
- Измеренный повтор класса прошёл 7/7. [Вывод](diagnostic-navigation-class.txt), [измерения](diagnostic-timing-class.txt). Поэтому задержка сверки установлена, но её единственная причинная связь с каждым таймаутом не доказана.
- Найден отдельный дефект отбора: `ReminderScheduler.eligible` использует `getSessionDetails(...).canEdit`, который запрещает будущую дату. Это исключает будущие занятия из планирования alarm. Дополнительная проверка создала завтрашнее занятие, подтвердила read-only результата и не нашла зарегистрированный RTC_WAKEUP на его начало: 1 случай, 1 failure, 0.861 s. [Вывод](baseline-future-alarm.txt), [исходник направленной проверки](future-alarm-regression-snippet.txt).
- Текущий отбор также читает расписание и подробности тем отдельно для каждого занятия. Сохранение экрана ждёт этот отбор через `ReminderScheduler.reconcile`.

## Передача дальнейшей работы

По уточнению пользователя правки будут выполняться в отдельном чате с простой моделью. Пробный helper репозитория, изменение планировщика, новые тестовые методы и временные сообщения измерений удалены из исходников. Исходники приложения сверены с [исходными SHA-256](baseline-source-hashes.json), `git diff` отслеживаемых файлов пуст. Локальные APK пересобраны из восстановленных исходников.

Пробное исправление не принималось и не проверялось итоговым прогоном; эти материалы не являются отчётом готового исправления. Временный AVD остановлен и удалён. R06 и QA-001 не закрывались.

## Исправление — 2026-10-06

### Отбор и атомарность

Причина отсутствия будущего alarm — использование `getSessionDetails().canEdit`:
это разрешение записи результата только сегодня/вчера, а не разрешение планирования.
`ReminderScheduler` теперь получает `TrackerRepository.getReminderCandidates()`.
Репозиторий вычисляет вчерашнюю локальную дату внедрёнными `now`/`zone`; один
DAO-запрос соединяет занятия, курсы и расписания и проверяет наличие непройденных
тем через EXISTS. Это согласованный снимок в Room-транзакции без подробного чтения
тем/курса/расписания для каждого занятия и без изменения схемы v6.

Кандидаты — PENDING не старше вчера, активный курс, существующее расписание,
темы отсутствуют либо ещё не исчерпаны. Будущий результат остаётся read-only.
Отбор ничего не пишет; синхронизация PENDING → SKIPPED с D+2 не менялась.
Очистка неактуальных показанных уведомлений сохранена. `withCurrentReminders`
повторно получает актуальных кандидатов и проверяет время непосредственно перед
показом, внутри общей транзакции с действием; защита от lifecycle-гонок сохранена.

### Регрессии

В `ReminderTest` добавлены две проверки:

- Завтрашний старт уже зарегистрирован как системный RTC_WAKEUP (проверка
  `dumpsys alarm`), хотя результат read-only. Повторная сверка сохраняет alarm
  и не показывает будущих уведомлений. DONE/SKIPPED, пауза, завершение,
  исчерпание тем и удаление расписания отменяют регистрацию.
- Вчерашний PENDING сохраняет показанный вопрос, просроченный PENDING и отмеченные
  результаты очищаются. Повторная сверка не меняет занятия/темы и не создаёт
  лишних вопросов; неактивные состояния также очищают вопрос.

В `SessionResultRepositoryTest` добавлены две проверки: граница вчера с фиксированным
моментом 2026-09-26 22:30 UTC и переключением UTC/Europe/Moscow, продвижение
внедрённых часов на день, отсутствие записей при отборе, исключение отмеченных
результатов и неактивных курсов, курсов без расписания и курсов с исчерпанными темами курсов.

Новые фикстуры поначалу имели конфликты уникальных ключей занятий и неполное
состояние завершённого CourseEntity; они исправлены. Системные уведомления
обновляются асинхронно: новая проверка ожидает posting/cancellation существующим
5-секундным helper и проверяет только свои теги, исключая системную сводку Android.
Падения промежуточных регрессий сохранены в логах; они не объявляются успешными.
Тайм-ауты исходных тестов и assertions не менялись.

### Окружение и направленные результаты

Новый отдельный AVD `ScheduleReturnFix`, API 36, `emulator-5558`, cold boot,
Europe/Moscow, 320 × 540 dp. Пользовательские AVD/телефон не изменялись.
Все adb-команды содержат `-s emulator-5558`. APK установлен через `install -r`;
реальная БД наполнена повторяемым `R05EmulatorSeedTest`, данные затем не очищались.
[Окружение](fix-environment.txt), [наполнение](fix-seed.txt).

Из `tracker-app/`, JDK 17 и SDK по README:

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --console=plain
```

Сборка/lint/androidTest успешны, 33/33 JVM; lint 0 ошибок / 35 предупреждений.
[Итоговая сборка](fix-build-final.txt).

Команды instrumentation после установки обоих APK (JDK/SDK как выше):

```sh
adb -s emulator-5558 shell am instrument -w -e class com.utbildning.tracker.ui.R05EmulatorSeedTest -e seedR05 true com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5558 shell am instrument -w -e class com.utbildning.tracker.notifications.ReminderTest,com.utbildning.tracker.data.SessionResultRepositoryTest,com.utbildning.tracker.ui.AppNavigationTest com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5558 shell am instrument -w -e class 'com.utbildning.tracker.notifications.ReminderTest,com.utbildning.tracker.data.SessionResultRepositoryTest,com.utbildning.tracker.ui.AppNavigationTest#unscheduledCourseCanCancelEnableAndEditThroughPencil' com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5558 shell am instrument -w -r -e reviewHeightDp 540 -e timeout_msec 120000 com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
```

ReminderTest 6/6, SessionResultRepositoryTest 13/13 и исходный navigation-метод
отдельно 1/1: всего 20/20, 18.271 s. [Итоговые регрессии](fix-regressions-final.txt).
AppNavigationTest прошёл 7/7 в трёх предыдущих направленных прогонах с тем же
кодом приложения, включая повтор без очистки. В этих составных прогонах были
только перечисленные выше ошибки новых фикстур:
[первый](fix-focused.txt), [повтор](fix-focused-repeat.txt),
[третий](fix-focused-final.txt).

### Полный instrumentation

Один полный прогон на той же заполненной БД: **202 случая, 196 успешных,
1 failure, 5 assumption-пропусков**, 507.298 s.
[Полный raw-вывод](fix-full-runner.txt), [сводка статусов](fix-full-summary.json).
AppNavigationTest прошёл 7/7, ReminderTest и новые репозиторные регрессии прошли;
существующие проверки реальной обычной доставки RU/EN и перехода из уведомления
также прошли. Это не проверка ожиданием доставки именно завтрашнего занятия.

Assumption-пропуски: OvernightAlarmDeliveryTest (RTC-перенос через полночь),
два opt-in EmulatorDemoSeedTest, EmulatorLongTitleSeedTest и R05EmulatorSeedTest.
Последний отдельно выполнен при наполнении. Пропуски не считаются успешными
проверками бизнес-сценариев.

Единственный failure — `R05EditorReviewTest.russianEditorAndKeyboard`,
`assertAboveKeyboard` на шаге очистки категории (строка 83).
В логах фактические bounds кнопки «Применить список» — `Rect(423, 668 - 745, 721)`,
IME — `Rect(0, 675 - 839, 1417)`:
[системные координаты и стек](fix-full-diagnostic-logcat.txt).
[Снимок окна категории](fix-full-ru-category-keyboard.png) просмотрен:
действия окна действительно перекрыты IME в этом состоянии. Предыдущий шаг тем
прошёл и кнопки видны [на снимке](fix-full-ru-topics-keyboard.png).
Изолированный повтор без изменения кода и очистки БД — **1/1**, 24.484 s:
[лог](fix-ime-isolated.txt). Это отдельная неустойчивость IME-проверки/окна категории;
её причина и связь с планировщиком не установлены. UI-код и тест не менялись,
полный набор **не считается зелёным**, успешный повтор не отменяет failure.

### Возврат из расписания

После пакетного отбора исходный `unscheduledCourseCanCancelEnableAndEditThroughPencil`
не повторил таймаут в трёх направленных прогонах класса, полном наборе и отдельном прогоне на заполненной БД.
`ScheduleScreen` и `AppNavigationTest` не изменялись. Подтверждённая дорогая сверка
с отдельными чтениями каждого занятия устранена, но это не доказывает её единственной
причинной связи с прежним падением. Причина исходного navigation-таймаута
остаётся неустановленной; измеренного падающего прогона после исправления пока нет.
Временные диагностические сообщения в исходники не добавлялись.

### Ограничения

Регистрация будущего alarm проверена, фактическое ожидание его доставки завтра
не выполнялось. Ночные ожидания, reboot, разрешения API 33 и физическое устройство
в этой задаче не повторялись; прежние результаты сохранены отдельно.
R06 открыт до пользовательских замечаний/подтверждения; R03 целиком и QA-001
не закрываются. Три полных прогона QA-001 не запускались. Новый тестовый AVD остановлен; его данные сохранены. Commit/push не выполнялись,
неотслеживаемые логи QA-001 сохранены.
