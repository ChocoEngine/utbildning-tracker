# Аудит тестов по текущему поведению

Проверка 2026-10-29. Основание — `PRODUCT_SPEC.md` и `BACKUP_CONTRACT.md`
на базе `fa1d3ef94b6b9d7890ee624335a2ab98e674f889` с рабочими изменениями
этого аудита. Изменений продуктовой логики нет; коммит и push не выполнялись.

## Соответствие и избыточность

Тесты приложения не читают план, номера этапов и чекбоксы. Удалены старые
обозначения этапов из комментария, имён временных файлов и снимка иконки.
У оркестратора синтетические задачи названы `T01`/`T02`; чтение произвольного
Markdown-плана — его собственное поведение, а не зависимость от плана приложения.

Удалены два повторения: Android round-trip архива (есть JVM round-trip и
неизменяемая v1-копия) и `automaticArchiveCanBeValidatedAndRestored`, который
не запускал worker (экспорт и импорт уже проверяются отдельно). Настоящие
прогоны worker, ротация файлов, ошибка провайдера и сохранность ручной копии
остались. Сквозные тесты восстановления и жизненного цикла сохранены: они
проверяют сочетание операций и повторное открытие БД, а не только отдельные методы.

Добавлены проверки неизвестной обязательной возможности, нового minor и
диагностической версии БД, неизвестных необязательных полей, повторного JSON-ключа,
неизвестного результата и отсутствующего поля при правильном checksum.
Отдельно проверены границы редактирования занятия и диапазон хранения истории.

На API 33 воспроизведён сбой `failedCategoryAddKeepsInputAndAllowsRetryWithoutSelectingIt`:
тест ожидал только публикацию строки в Room, затем сравнивал весь `AnnotatedString`,
хотя требование относится к тексту поля. Это не гарантировало готовность UI;
в диагностике после сбоя поле уже было пустым. Теперь тест ждёт пустой текст
и проверяет его без привязки к оформлению. Точный вклад асинхронного обновления
и метаданных `AnnotatedString` отдельно не доказан. Произвольная задержка не
добавлялась, продуктовый результат не ослаблен.

В `NotificationShadeResultTest` прямое создание уведомления зависело от
изменяемых ресурсов сохранённого `Application Context` после открытия/закрытия
Activity: выбранный язык мог оставаться `ru`, а эти ресурсы уже были `en`.
Матрица действий теперь использует явный локализованный контекст своих данных,
без повторного сброса языка перед каждым случаем. Клики настоящей шторки,
язык экрана выбора тем и результаты БД проверяются как прежде. Распространение
языка в настоящем AlarmManager/receiver проверяется отдельно в
`LocalizedReminderDeliveryTest`, без подстановки локализованного контекста.

## Матрица текущих правил

| Договорённость | Автоматическое доказательство |
|---|---|
| Названия 50/20 code points, атомарность, 10 цветов включая паузу, категории без автоматического выбора | `TrackerRepositoryTest`, `CourseFormRepositoryTest`, `CoursesScreenTest` |
| Обрезка тем до 100 code points при применении, ID/порядок/пройденные темы, конфликт и отмена | `TopicListEditorTest`, `TopicListRepositoryTest`, `CoursesScreenTest`, `CourseDraftRecreationTest` |
| Долгое нажатие, короткий тап/прокрутка без изменения, ручная отметка без календаря | `ManualTopicCompletionTest`, `CoursesScreenTest`, `CourseEditorLayoutTest` |
| Независимые темы/расписание, пауза/продолжение, завершение, перезапуск и освобождение цвета | `CourseLifecycleRepositoryTest`, `CourseLifecycleScreenTest`, `CourseLifecyclePersistenceTest`, `BackupLifecycleIntegrationTest` |
| Дни и время, включительная дата начала, ночь/90 минут, DST, пересечения только как предупреждение | `ScheduleGeneratorTest`, `ScheduleOverlapTest`, `ScheduleRepositoryTest`, `ScheduleScreenTest` |
| Генерация вперёд, дальний месяц, отсутствие дублей и заполнения прошлых пробелов, сохранность результатов при правке | `ScheduleRepositoryTest`, `CalendarIntegrationTest`, `AppNavigationTest` |
| Сегодня/вчера редактируются, прошлое/будущее только просмотр, догоняющий SKIPPED с +2 дня | `SessionEditPolicyTest`, `SessionResultRepositoryTest`, `CalendarSessionStatusTest`, `CalendarTodayScreenTest`, `SessionScreenTest` |
| Любое число тем при DONE, SKIPPED не отмечает темы, устаревшее действие не меняет результат | `SessionResultRepositoryTest`, `SessionScreenTest`, `ReminderTest`, `NotificationShadeResultTest` |
| Настоящий alarm, RU/EN, тело открывает занятие, действия шторки и холодный запуск | `LocalizedReminderDeliveryTest`, `ReminderNavigationTest`, `NotificationShadeResultTest` |
| Пауза/завершение/удаление/исчерпание отменяют напоминания; один переключатель и системные разрешения | `ReminderTest`, `ReminderRecoveryTest` (обычная фаза), `SettingsRestoreUiTest` |
| История: 365 дней по умолчанию, 1–3650/отключено, строгая граница, сохранность остальных данных | `RetentionPolicyTest`, `CalendarCleanupTest`, `RetentionSettingsTest` |
| ZIP+JSON v1, совместимость, повреждения, атомарная полная замена и продолжение после прерывания | `BackupArchiveJvmTest`, `BackupArchiveTest`, `BackupSnapshotTest`, `BackupImportTest`, `SettingsRestoreUiTest` |
| Общая папка копий, фоновые копии, ротация только своих файлов, отказ провайдера | `AutomaticBackupTest`, `SettingsRestoreUiTest`, `SafBackupFolderTest` (локальная папка, отдельный системный запуск) |
| Язык/гайд, навигация, сохранение черновиков и данных после recreation, узкий экран и геометрия | `LanguageSettingsTest`, `AppNavigationTest`, `CourseDraftRecreationTest`, UI-проверки ниже |
| Схема v8 и сохранность старых данных | `TrackerDatabaseTest`, `TrackerMigrationTest`, `SchemaSimplificationMigrationTest`, `ExhaustionMigrationTest`; это не заменяет установку поверх прежнего APK |

Отменённые перенос отдельного занятия, сброс тем при смене расписания и экран
категорий в настройках не сделаны условиями успеха. Существующие проверки
геометрии и semantics сохранены; отдельный отложенный этап доступности не начат.

## Состав набора

Ниже число методов, а не число внутренних вариантов: например, один тест шторки
проверяет матрицу языков, дат, наличия тем и действий. Чистое создание снимков и
демозаполнение не засчитываются продуктовым покрытием. Полные команды — в `TESTING.md`.

| Группа / класс | Методов | Назначение |
|---|---:|---|
| JVM: `BackupArchiveJvmTest` | 19 | регрессия |
| JVM: `CalendarSessionStatusTest` | 2 | регрессия |
| JVM: `LocalDayTest` | 3 | регрессия |
| JVM: `ScheduleGeneratorTest` | 7 | регрессия |
| JVM: `ScheduleOverlapTest` | 8 | регрессия |
| JVM: `SessionEditPolicyTest` | 2 | регрессия |
| JVM: `TopicListEditorTest` | 11 | регрессия |
| JVM: `TopicPaceTest` | 4 | регрессия |
| JVM: `RetentionPolicyTest` | 5 | регрессия |
| Android: `AutomaticBackupTest` | 8 | регрессия |
| Android: `BackupArchiveTest` | 2 | регрессия |
| Android: `BackupImportTest` | 6 | регрессия |
| Android: `BackupLifecycleIntegrationTest` | 1 | регрессия |
| Android: `BackupSnapshotTest` | 3 | регрессия |
| Android: `SafBackupFolderTest` | 1 | отдельный системный сценарий, четыре фазы в отдельных процессах |
| Android: `CourseFormRepositoryTest` | 8 | регрессия |
| Android: `CourseLifecyclePersistenceTest` | 1 | регрессия |
| Android: `CourseLifecycleRepositoryTest` | 10 | регрессия |
| Android: `ManualTopicCompletionTest` | 3 | регрессия |
| Android: `ScheduleRepositoryTest` | 15 | регрессия |
| Android: `SessionResultRepositoryTest` | 13 | регрессия |
| Android: `TopicListRepositoryTest` | 11 | регрессия |
| Android: `TopicPaceRepositoryTest` | 1 | регрессия |
| Android: `TrackerRepositoryTest` | 14 | регрессия |
| Android: `ExhaustionMigrationTest` | 1 | регрессия |
| Android: `SchemaSimplificationMigrationTest` | 2 | регрессия |
| Android: `TrackerDatabaseTest` | 9 | регрессия |
| Android: `TrackerMigrationTest` | 5 | регрессия |
| Android: `CalendarCleanupTest` | 4 | регрессия |
| Android: `LocalizedReminderDeliveryTest` | 1 | регрессия |
| Android: `NotificationShadeResultTest` | 1 | регрессия |
| Android: `OvernightAlarmDeliveryTest` | 1 | отдельный системный сценарий |
| Android: `ReminderNavigationTest` | 2 | регрессия |
| Android: `ReminderRecoveryTest` | 1 | регрессия |
| Android: `ReminderTest` | 7 | регрессия |
| Android: `AppHeaderTest` | 1 | регрессия |
| Android: `AppNavigationTest` | 7 | регрессия |
| Android: `CalendarIntegrationTest` | 1 | регрессия |
| Android: `CalendarTodayScreenTest` | 7 | регрессия |
| Android: `CourseDetailsReviewTest` | 2 | регрессия |
| Android: `CourseDraftRecreationTest` | 2 | регрессия |
| Android: `CourseEditorKeyboardTest` | 4 | регрессия |
| Android: `CourseEditorLayoutTest` | 2 | регрессия |
| Android: `CourseLifecycleScreenTest` | 9 | регрессия |
| Android: `CourseListAppearanceTest` | 4 | регрессия |
| Android: `CourseListGeometryReviewTest` | 2 | регрессия |
| Android: `CourseTopicPaceTest` | 2 | регрессия |
| Android: `CoursesScreenTest` | 30 | регрессия |
| Android: `EmulatorAcceptanceSeedTest` | 1 | наполнение, не проверка |
| Android: `EmulatorDemoSeedTest` | 2 | наполнение, не проверка |
| Android: `EmulatorLongTitleSeedTest` | 1 | наполнение, не проверка |
| Android: `LanguageSettingsTest` | 7 | регрессия |
| Android: `LauncherIconMaskTest` | 1 | регрессия |
| Android: `OpenMidnightTest` | 1 | отдельный системный сценарий |
| Android: `RetentionSettingsTest` | 2 | регрессия |
| Android: `ScheduleOverlapReviewTest` | 2 | регрессия |
| Android: `ScheduleScreenTest` | 12 | регрессия |
| Android: `SessionScreenTest` | 13 | регрессия |
| Android: `TodayVisualReviewTest` | 2 | снимки для просмотра |
| Android: `VisualReviewTest` | 2 | снимки для просмотра |
| Android: `SettingsRestoreUiTest` | 6 | регрессия |
| Утилита: `CodexRunnerTest` | 3 | регрессия |
| Утилита: `GitRepositoryTest` | 1 | регрессия |
| Утилита: `MarkdownTaskPlanTest` | 2 | регрессия |
| Утилита: `TaskSchedulerTest` | 3 | регрессия |

## Измерение JVM-покрытия

JaCoCo 0.8.13, задача `:app:jvmCoverageReport`. Включены `domain/**`,
`BackupArchive*`, `RetentionPolicy*`. Измерение не включает Room, Compose,
WorkManager, SAF, AlarmManager, DTO копии и сгенерированную Room-реализацию;
процент для всего приложения не вычислялся. Ветви Kotlin включают технические
проверки, поэтому процент не равен доле продуктовых правил.

| Область | Строки | Ветви |
|---|---:|---:|
| domain | 122/123 (99,2%) | 118/130 (90,8%) |
| RetentionPolicy | 9/9 (100%) | 16/16 (100%) |
| BackupArchive | 275/285 (96,5%) | 153/209 (73,2%) |
| Вся указанная JVM-область | 406/417 (97,4%) | 287/355 (80,8%) |

Отчёт: `tracker-app/app/build/reports/jacoco/jvmCoverageReport/`
(`html/index.html` и `jvmCoverageReport.xml`). Пробелы ветвей архива остаются;
ни 100% покрытия кода, ни полнота всех возможных входов не заявляются.

## Окружение и результаты

JDK Temurin 17.0.13, Gradle 8.13, SDK 36. `versionCode=1`, `versionName=0.1.0`.
Оба AVD: 1080×2400, 420 dpi, системный язык en-US. У API 36 `font_scale=1.0`;
у API 33 явная системная настройка `font_scale` отсутствует (`null`).
Отдельные тесты явно задают RU/EN и узкий экран. Временная зона Europe/Moscow.
API 36: выделенный `F17_API_36`, serial `emulator-5554`,
`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys`.
API 33: временная копия `Pixel_6`, `-read-only -no-snapshot -no-window -port 5556`,
serial `emulator-5556`,
`google/sdk_gphone64_arm64/emu64a:13/TE1A.240213.009/12342917:userdebug/dev-keys`.
Исходный API 33 AVD не очищался: `pm clear` выполнен только в его временной копии.
Первый запуск на скопированных демоданных прерван из-за занятых цветов и не
засчитан. На чистой базе первый полный API 33 прогон дал 244/245 и описанный
сбой проверки категории. В следующем прогоне обнаружилась зависимость языковой
фикстуры от сохранённого `Application Context`; этот прогон остановлен и не засчитан.

APK приложения (одинаковый во всех сериях):
`a9172e5658e0302d0f48d84e4a91adcc3db767af8161eb0f123828f1fdc23eea`.
Тестовый APK до исправления ожидания категории:
`91ee8f9fbc4ba37041a6dd0df6dcd10e560bd0b3b07372d769e8925ddd2870b9`.
Тестовый APK после исправления категории (на нём проверены ночные сценарии):
`ee0f56f64f2168f9b712edcfbf6c04b23b2a0bdd19b87e7088db28ee2127ae32`.
Финальный тестовый APK после стабилизации языковой фикстуры:
`7505c21032f41735ae32f74daa44c9fd4844b9320a2dd1cc5058a31ce19636d7`.
SHA-256 состава тестовых входов (пути и байты файлов `src/test`, `src/androidTest`
приложения, `src/test` оркестратора и `app/build.gradle.kts`, отсортированные,
разделитель NUL между путём/содержимым/следующим файлом):
`994f74688bd177d7880e54acdeeb12cb7c6c969d68e8545deddce17d3809dd54`.

| Проверка | Результат | Граница |
|---|---|---|
| JVM приложения | 61/61, 0 ошибок/пропусков | XML `app/build/test-results/testDebugUnitTest` |
| JVM оркестратора, `test --rerun-tasks` | 9/9, 0 ошибок/пропусков | XML `tools/task-orchestrator/build/test-results/test` |
| Сборка обоих APK, lint, JVM coverage | BUILD SUCCESSFUL; lint: 0 errors, 50 warnings | Предупреждения относятся к production-коду/ресурсам/зависимостям; в test/androidTest предупреждений нет. Компиляция androidTest не считается исполнением |
| API 33, полный регрессионный набор, финальный APK | 245/245, 0 failures/errors/skips | 495,485 с; полный лог `/private/tmp/utbildning-test-audit-20261029/api33-complete.txt`; проверены 245 success-маркеров, а не только строка OK |
| API 36, полный регрессионный набор, APK до исправлений ожидания категории/языковой фикстуры | 245/245, 0 failures/errors/skips | XML сохранён в `/private/tmp/utbildning-test-audit-20261029/api36-regression`; изменённые классы повторены отдельно на финальном APK |
| API 36, изменённые классы и генераторы снимков, финальный APK | XML: 35/35, 0 failures/errors/skips | 31 регрессионный метод (`CoursesScreenTest`, `NotificationShadeResultTest`) и 4 генератора (`VisualReviewTest`, `TodayVisualReviewTest`); внешний вид снимков не принят автоматически |
| API 36 до разделения групп | XML: 257 методов, 251 успех, 6 assumption-пропусков, 0 failures/errors | Старый состав; счётчик Gradle в консоли завышал число завершений на пропусках |
| API 33, OpenMidnightTest, APK после исправления категории | 1/1, 47,155 с, без пропуска | Часы 2026-10-29 23:59:15 → 2026-10-30 00:00; первоначальный вызов до подготовки часов пропущен и не засчитан |
| API 33, OvernightAlarmDeliveryTest, APK после исправления категории | 1/1, 60,451 с, без пропуска | Часы 2026-10-30 00:59 → 01:00; вчерашние занятия подготовлены перед доставкой, непрерывное ожидание от 23:30 не проверялось |

Часы временного AVD после ночной серии возвращены к времени хоста,
`auto_time=1` восстановлен. После финального прогона временная read-only копия
AVD API 33 закрыта без сохранения изменений; исходный AVD сохранён.
Команды: сборка/JVM/lint/coverage и обычный
`connectedDebugAndroidTest` — как в `TESTING.md` с JDK 17 и
`ANDROID_SERIAL=emulator-5554`. Для API 33 после `adb install -r` обоих APK:

```sh
adb -s emulator-5556 shell am instrument -w -e notAnnotation com.utbildning.tracker.SystemScenario,com.utbildning.tracker.EmulatorFixture,com.utbildning.tracker.ScreenshotCapture com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
```

На API 36 повтор изменённых классов и создание снимков выполнены командой:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -PincludeAuxiliaryTests=true -Pandroid.testInstrumentationRunnerArguments.class=com.utbildning.tracker.ui.CoursesScreenTest,com.utbildning.tracker.notifications.NotificationShadeResultTest,com.utbildning.tracker.ui.VisualReviewTest,com.utbildning.tracker.ui.TodayVisualReviewTest --console=plain
```

XML обеих API 36 серий сохранены раздельно в
`/private/tmp/utbildning-test-audit-20261029/api36-regression` и
`api36-affected-final`. Это разные тестовые APK с одинаковым APK приложения;
полный прогон и направленный повтор не выдаются за один запуск.
Имена всех 245 методов обычного Android-набора сверены с XML полного API 36
прогона: отсутствующих и неожиданных методов нет.

Для ночных запусков использовался тот же runner с `-e class` соответствующего
класса, без `notAnnotation`. Настройка часов выполнена через root ADB только
во временной копии AVD: `settings put global auto_time 0`,
`date 102923592026.15` и `date 103000592026.00`, затем восстановлены время и auto_time.

## Что эти тесты не подтверждают

- Облачные SAF-провайдеры и перенос доступа между устройствами. Реальный выбор
  локальной папки и доступ после остановки процесса теперь проверяются отдельно
  в `SafBackupFolderTest` (результат ниже).
- Обновление поверх прежнего APK с инвентаризацией всех пользовательских данных;
  тесты миграций и `adb install -r` тестового APK сами по себе этого не доказывают.
- Внешние фазы настоящего reboot и отзыва/возврата разрешений у
  `ReminderRecoveryTest`; обычная фаза проверяет только свою часть сценария.
- Реальное семидневное/суточное ожидание WorkManager, особенности физических
  устройств и прошивок; тесты worker проверяют управляемый запуск.
- Полную визуальную приёмку сохранённых снимков и личные курсы пользователя.
  Файлы наполнения не запускались и не считаются тестами. Отложенный этап
  доступности не выполнялся. Инженерная и пользовательская приёмка остаются
  в открытой работе; этот аудит не закрывает их автоматически.


## Дополнение: сквозной SAF-тест — 2026-10-29

Добавлены `SafBackupFolderTest` и `tracker-app/scripts/test-saf-folder.py`.
Один системный сценарий выполняется в четырёх отдельных instrumentation-запусках:
`prepare`, `verify`, `cleanup`, `check_cleanup`; между ключевыми фазами скрипт
делает `am force-stop`. Новый процесс дополнительно проверяется случайным
маркером процесса. Тест не подставляет результат выбора и не выдаёт URI-права
самостоятельно: папка создаётся и выбирается через настоящий DocumentsUI,
постоянные read/write grant получает обработчик приложения. Копии создаются
кнопкой приложения, читаются и валидируются через провайдер. После перезапуска
должны читаться первая копия и новая копия с изменённым названием курса;
первый архив сохраняет прежний payload. Уборка удаляет только UUID-папку и
курс теста, освобождает grant и восстанавливает язык/исходные настройки.

На той же базе HEAD `fa1d3ef94b6b9d7890ee624335a2ab98e674f889` с рабочими
изменениями сборка androidTest и lint успешны. Production-код не менялся.
Новый сценарий исключён из обычного набора через `SystemScenario`; обычные
245 методов повторно не запускались. Их более ранние результаты выше относятся
к указанным там тестовым APK. Текущее число Android-методов — 256, включая
фикстуры/генераторы/системные сценарии; в обычном наборе по-прежнему 245.

| AVD | prepare | verify после остановки | cleanup | check_cleanup после остановки |
|---|---:|---:|---:|---:|
| `F17_API_36`, `emulator-5554`, API 36 | PASS, 9,449 с | PASS, 1,540 с | PASS, 0,088 с | PASS, 0,044 с |
| Временная read-only копия `Pixel_6`, `emulator-5556`, API 33 | PASS, 4,385 с | PASS, 2,038 с | PASS, 0,187 с | PASS, 0,060 с |

Каждая фаза дала один реально выполненный успешный метод, без assumptions.
Команды из корня репозитория:

```sh
python3 tracker-app/scripts/test-saf-folder.py --serial emulator-5554
python3 tracker-app/scripts/test-saf-folder.py --serial emulator-5556
```

Логи фаз и SHA-256 исходников/скрипта/APK сохранены в
`/private/tmp/utbildning-saf-folder-20261029/` (`api36/`, `api33/`, `sha256.json`).
SHA-256 приложения остаётся
`a9172e5658e0302d0f48d84e4a91adcc3db767af8161eb0f123828f1fdc23eea`;
новый тестовый APK:
`330b8a0000cf2b419c7830208c5265905c50fd6389d4d1128e91b9969de55140`.
Временный API 33 AVD закрыт без сохранения изменений исходного образа.

При разработке были неуспешные проверки навигации по старым элементам
DocumentsUI, затем проверки URI папки в корне тома и сброса асинхронных prefs
при выходе instrumentation. Они не засчитаны как успех. Финальная версия
создаёт папку через диалог, поддерживает `primary:NAME` и вложенный URI,
синхронно фиксирует уборку и проверяет её отдельным запуском. Ограничения:
локальный ExternalStorageProvider; облако, reboot, перенос между устройствами
и полный импорт с заменой БД этим сценарием не подтверждаются.
