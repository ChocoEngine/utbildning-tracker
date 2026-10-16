# Стабилизация полного instrumentation-набора

QA-001 закрыт 2026-10-08 для `codex/version-01`: три последовательных полных
прогона одной сборки без failures. [Новый результат](#qa-001--три-последовательных-прогона-2026-10-08).
Исторический отчёт 2026-10-05 ниже сохранён.

Проверено 2026-10-05. Падения полного API 36 instrumentation-набора разобраны
по первому воспроизводимому сбою и по зависимостям от порядка. Продуктовые правила
R02/R03 не менялись. Пробное изменение порядка закрытия окна создания курса было
полностью отменено после того, как показало шесть новых падений старых сценариев;
в итоговом diff этой задачи изменены только instrumentation-тесты и документация.

## Исходное состояние и окружение

- ревизия: `fbbcf9f29411740584f33e1cebdddb07f4e3129a`;
- рабочая копия уже содержала незакоммиченные изменения R02-T01 и документы;
  они сохранены, commit/push не выполнялись;
- JDK `17.0.13`, Gradle `8.13`, Android Gradle Plugin `8.13.2`, Kotlin `2.3.10`;
- Android Emulator `36.4.10.0`, отдельный AVD `Medium_Phone_API_36`, serial
  `emulator-5554`, API 36, build `BE2A.250530.026.D1`;
- cold boot без загрузки snapshot; пользовательский `Pixel_6` не запускался и его
  приложение/данные не изменялись;
- стандартный экран: `1080 × 2400 px`, density `420`, конфигурация приложения
  `411 × 914 dp`; узкая проверка: override `840 × 1418 px`, то есть `320 × 540 dp`;
- system locale `en-US`, app locale не переопределён, timezone `Europe/Moscow`;
- полные прогоны выполнялись без demo flags и без демо-наполнения. Очищалась только
  тестовая установка на отдельном AVD.

Текущий baseline до исправлений выполнил 194 теста успешно на стандартной высоте.
Это подтвердило недетерминированность прежней группы из 10 UI-падений, но не
опровергло отдельный дефект сценария 540 dp. Число 194 уже было в свежем baseline;
стабилизация не добавляла и не удаляла `@Test`. Отличие от исторических 193 связано
с составом существующей рабочей копии после отчёта R02-T01.

## Установленные причины

| Сценарий | Ожидалось | Фактическое состояние и порядок | Причина | Исправление |
|---|---|---|---|---|
| `CalendarTodayScreenTest.todayShowsYesterdayButOmitsOlderAndFutureSessions` | после выбора вчерашнего занятия выбрать сегодняшнее `default` | на `320 × 540 dp` `session_done_default` находился ниже созданной части `LazyColumn`; `performScrollTo()` падал в `CalendarTodayScreenTest.kt:176`, потому что целевого semantics-узла ещё не было | неверное ожидание lazy UI в тесте | прокрутка контейнера `today_sessions` через `performScrollToNode(hasTestTag(...))`, затем прежняя проверка результата |
| `CourseDraftRecreationTest.actualActivityRecreationRetainsCreatedCourseAndOpenTopicEditor` | создать курс, открыть категорию, затем проверить реальное recreation | отдельно проходил; после `CourseDetailsReviewTest.russian` падал до `scenario.recreate()` сначала на `course_name`, затем на `course_category` (`CourseDraftRecreationTest.kt:71/75`) | тест ждал узел до клика, но не ждал появления следующего экрана и завершения `attach` | ожидание `course_name`, стабильного `draft.id` и поля категории до действий; lifecycle/recreation assertion сохранён |
| несколько методов `CoursesScreenTest` | работать с уже открытым диалогом/редактором и загруженным списком | число и место сбоев менялись: отсутствовали `course_name`, `course_title`, `course_schedule`, `course_resume`; один тест повторно вызывал `setContent`, тема была только в unmerged tree | асинхронные UI/Room-переходы и ошибочный semantics tree в тестах | общий `waitForTag`, ожидание загруженных строк/Flow, единственный `setContent`, проверка темы в unmerged tree; assertions не удалены |
| `SessionScreenTest.choosingSeveralTopicsOutOfOrderPersistsSingleDoneSession` | два выбора и одно сохранение DONE | в полном порядке отложенная recomposition пересеклась с Room transaction; `Choreographer` создавался на потоке без Looper | тест отправлял последовательные действия без Compose-idle | ожидание узла и `waitForIdle()` после каждого семантического клика |
| `ReminderNavigationTest.questionActionIntentsRunThroughReceiverAndColdActivityIntoRepository` | два question notification и действия Skip/Done в каждом запуске | первый полный прогон проходил, повтор без очистки не создавал уведомления на строке 129 | фиксированные IDs `action_skip`/`action_done` уже находились в долговечном `reminders.delivered`; производственная дедупликация работала корректно | уникальные session IDs на каждый запуск; дедупликация и cleanup приложения не ослаблены |

Первое recreation-падение не было причиной всех последующих ошибок: оно происходило
до recreation, а календарный, экранные, Room/Compose и notification-сбои отдельно
воспроизведены со своими причинами. Утечка Room, `AppContainer`, locale или
ViewModelStore в recreation-тесте не подтверждена. Подтверждённых дефектов
приложения по итогам нет.

## Направленные проверки

- календарный сценарий: `1/1` на стандартной высоте и `1/1` на `320 × 540 dp`;
- `CourseDetailsReviewTest` → recreation в порядке полного набора: `5/5`;
- `CoursesScreenTest`: `24/24`;
- `CoursesScreenTest` + `SessionScreenTest`: `37/37`;
- три последних падавших метода: `3/3`;
- notification action-тест два раза подряд без очистки: `1/1` и `1/1`.

Ключевые артефакты:

- [календарь до исправления](logs/calendar-540-before-runner.txt) и
  [относящийся logcat](logs/calendar-540-before-logcat.txt);
- [recreation отдельно](logs/recreation-isolated-runner.txt),
  [после предшествующего класса до исправления](logs/recreation-with-predecessor-runner.txt)
  и [относящийся logcat](logs/recreation-with-predecessor-logcat.txt);
- [календарь 540 dp после исправления](logs/calendar-540-fixed-runner.txt);
- [нестабильные UI-классы после исправлений](logs/courses-session-classes-final-runner.txt);
- [повтор notification action 1](logs/reminder-actions-unique-1-runner.txt) и
  [повтор 2](logs/reminder-actions-unique-2-runner.txt).

## Итоговые проверки

Из `tracker-app/` с JDK 17 и Android SDK из README:

```text
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL
lint: 0 errors, 35 warnings
JVM: 33/33
```

После установки обоих APK с явным `adb -s emulator-5554` выполнены два
последовательных полных прогона одной и той же финальной сборки:

```text
adb -s emulator-5554 shell am instrument -w -e timeout_msec 120000 \
  com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner

clean run:  OK (194 tests), 637.425 s
repeat:     OK (194 tests), 617.091 s
```

Полный вывод: [чистый прогон](logs/final-postfix-full-run-1-runner.txt) и
[повтор без очистки](logs/final-postfix-full-run-2-runner.txt).

Четыре environment-gated сценария были assumption-пропусками и не считаются
выполненными бизнес-проверками: один RTC-сдвинутый `OvernightAlarmDeliveryTest`,
два `EmulatorDemoSeedTest` и один `EmulatorLongTitleSeedTest`. Демо-фикстуры в
полных прогонах не создавались.

## Ограничения и следующий шаг

- По критерию QA-001 из `POST_MVP_PLAN.md` нужны три последовательных чистых полных
  прогона. Здесь получены два последовательных зелёных прогона, из них первый после
  очистки, поэтому QA-001 формально не закрыт.
- Реальное ночное RTC-ожидание, demo seed, Android 13 и ручная визуальная приёмка
  этим заданием не повторялись.
- R02, R03 и R05 целиком не закрыты. Подтверждённых автоматических дефектов,
  блокирующих переход к функциональной и визуальной приёмке R05, не осталось.

## QA-001 — три последовательных прогона 2026-10-08

Критерий QA-001 выполнен на `codex/version-01`, HEAD
`5a36b90c14940b5900da0c15774fcb8f4d85e03a`, с незакоммиченным исправлением
только `NotificationShadeResultTest`. Переключение с version-02 выполнено по
уточнению пользователя до первого instrumentation-прогона. Функции version-02
и результаты F17 в эту проверку не входят. Commit/push не выполнялись.

### Окружение и порядок

Отдельный временный cold-boot AVD `QA001_API_36`, `emulator-5560`, Android 16 /
API 36, build `BE2A.250530.026.D1`, arm64, 1080 × 2400 px, density 420
(411 × 914 dp), системный язык en-US, Europe/Moscow, software GPU SwiftShader.
JDK 17.0.13, Emulator 36.4.10.0; версии Gradle/AGP/Kotlin закреплены проектом.
[Окружение и SHA-256 APK](logs/2026-10-08/environment.json).
Существующие AVD F17_API_36, ScheduleReturnFix и Pixel_6 не запускались и не
очищались. Новый AVD сначала наполнен идемпотентным R05 seed, затем очищена
только его тестовая установка для baseline. После приёмки демо-данные восстановлены.

Оба APK установлены один раз перед финальной серией. Перед первым прогоном
очищены данные приложения и test APK; второй и третий запущены без очистки,
переустановки, пересборки или перезагрузки. Между прогонами сохранялись Room,
preferences, locale и долговечная дедупликация уведомлений. Тесты сами выполняли
предусмотренное ими создание и cleanup фикстур; никакие методы не исключались.

```sh
# Из tracker-app/, JAVA_HOME и ANDROID_HOME — как в README.
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Один раз перед первым прогоном, только на выделенном QA001_API_36:
adb -s emulator-5560 shell pm clear com.utbildning.tracker
adb -s emulator-5560 shell pm clear com.utbildning.tracker.test
# Три последовательных вызова с одинаковыми аргументами:
adb -s emulator-5560 shell am instrument -w -e timeout_msec 120000 \
  com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
```

### Исправление теста и результаты диагностики

Исходная матрица создавала два занятия одного курса за одну дату для двух ответов;
это нарушает [контракт расписания](../../SCHEDULE_CONTRACT.md), а прогресс темы
имеет provenance по дате занятия. В независимых случаях SKIPPED не должен
наследовать прохождение из DONE. Из version-02 перенесено только тестовое
разделение матрицы на отдельный курс/занятие для каждого ответа, обновление
RU/EN-ресурсов и повторное раскрытие шторки при поиске кнопки.

Дополнительно helper теперь ищет кнопку в notification-строке с точным названием
текущего курса, нажимает реальный SystemUI-узел через ACTION_CLICK и повторяет
поиск только если система отклонила действие. Принятый клик не повторяется;
PendingIntent напрямую не вызывается. До действия проверяется публикация
конкретного notification tag; до сохранения темы — checked-состояние родительской
Compose-строки. Сохранены проверки результата Room, даты и прогресса обеих тем,
удаления уведомления и 16 комбинаций RU/EN × сегодня/вчера × темы/без тем × ответы.
Общие timeout не увеличивались, произвольные паузы, исключения тестов и изменения
приложения не добавлялись.

Подтверждены невалидная исходная фикстура, отклонённое accessibility-действие
устаревшего UI-узла и отсутствие проверки нужного notification-родителя в старом
helper. До уточнения target несколько запусков принимали клик, но текущая запись
оставалась PENDING; выбор старого notification-узла — гипотеза, единственная
причина каждого такого сбоя отдельно не доказана. Исправленный helper прошёл
три полных последовательных запуска. Отдельный временный диагностический
helper ошибочно искал checked на текстовом дочернем узле; accessibility-дерево
показало checked на родителе, и это наблюдение исправлено.

Один baseline попал в окно 00:57–00:59 и запустил реальное ожидание ночного alarm
в 01:00: тест превысил 120 секунд. Финальная серия выполнена после этого окна,
с тем же timeout и без исключения ночного теста; он прошёл по ожидаемому assumption.
Эта зависимость ночного сценария от стартовой минуты и лимита остаётся ограничением.

Неуспешные результаты не удалены: [исходный остановленный baseline](logs/2026-10-08/baseline-aborted-runner.txt),
[исходный тест отдельно](logs/2026-10-08/shade-isolated-before-runner.txt),
[полный прогон с одним failure](logs/2026-10-08/full-coordinate-failure-runner.txt),
[отклонённый узел](logs/2026-10-08/baseline-stale-node-aborted-runner.txt),
[публикация уведомления](logs/2026-10-08/baseline-posting-aborted-runner.txt),
[неизменившийся PENDING](logs/2026-10-08/baseline-action-target-aborted-runner.txt),
[ночное окно](logs/2026-10-08/baseline-night-window-aborted-runner.txt).
Принудительно остановленные baselines имеют `Process crashed` из-за явного
force-stop после первого failure и не объявляются полными результатами.
Промежуточные направленные логи сохранены в том же каталоге.
Последний [направленный тест](logs/2026-10-08/shade-scoped-runner.txt) успешен: 1/1.

### Три финальных полных прогона

| Прогон | Состояние | Время runner | Всего случаев | Успешно выполнено | Assumption | Failures |
|---|---|---|---:|---:|---:|---:|
| 1 | чистая установка | 420.996 s | 209 | 203 | 6 | 0 |
| 2 | без очистки | 477.678 s | 209 | 203 | 6 | 0 |
| 3 | без очистки | 466.088 s | 209 | 203 | 6 | 0 |

Runner пишет `OK (209 tests)`; число включает assumption-случаи, поэтому
оно не означает 209 выполненных бизнес-проверок. Состав пропусков одинаковый:
`OvernightAlarmDeliveryTest`, `OpenMidnightTest`, два режима `EmulatorDemoSeedTest`,
`EmulatorLongTitleSeedTest` и `R05EmulatorSeedTest`. `AppNavigationTest` прошёл
7/7 и матрица SystemUI — 1/1 в каждом запуске.

Runner: [1](logs/2026-10-08/full-1-runner.txt), [2](logs/2026-10-08/full-2-runner.txt),
[3](logs/2026-10-08/full-3-runner.txt).
Полный logcat без потерь сжат gzip: [1](logs/2026-10-08/full-1-logcat.txt.gz),
[2](logs/2026-10-08/full-2-logcat.txt.gz), [3](logs/2026-10-08/full-3-logcat.txt.gz).
[Сборка/JVM/lint](logs/2026-10-08/final-build.txt): BUILD SUCCESSFUL,
33/33 JVM, lint 0 ошибок / 37 предупреждений. Проверки последнего вызова Gradle
могут быть UP-TO-DATE; JVM реально выполнялись при первичной пересборке version-01.

QA-001 закрыт только для этой версии и окружения. Android 13, узкий экран 320 ×
540 dp, отдельная визуальная приёмка и успешное ночное ожидание в эту серию не
входят. R06/R07/S25 автоматически не закрываются и новые функции не начинаются.
