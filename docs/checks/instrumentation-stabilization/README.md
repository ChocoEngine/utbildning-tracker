# Стабилизация полного instrumentation-набора

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
