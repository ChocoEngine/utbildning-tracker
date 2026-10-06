# План оптимизации памяти и производительности

План составлен по результатам статического аудита и динамической проверки
2026-10-06. Классическая утечка `Activity` не воспроизвелась: после 20
пересозданий и принудительной GC осталась одна актуальная `Activity`. Основные
риски связаны с неограниченным числом Room-подписок, чтением полной истории и
повторными запросами.

Задачи выполнять по одной в указанном порядке. Не переходить к следующей без
отдельного запроса. После выполнения обновлять чек-лист и кратко записывать
фактические проверки и оставшиеся ограничения.

## Общие правила

- Не менять пользовательское поведение, интерфейс и бизнес-правила.
- Сохранять посторонние изменения рабочего дерева.
- Не запускать полный instrumentation-набор в отдельных задачах PERF-01–PERF-06.
- Не изменять схему Room, кроме PERF-05.
- Команды выполнять из `tracker-app/` под JDK 17.
- Если минимальная проверка падает из-за существующей проблемы вне задачи,
  зафиксировать это отдельно и не расширять объём изменений.

Минимальная команда для задач без миграции:

```bash
/usr/bin/env JAVA_HOME=/Users/alxshvarts/Library/Java/JavaVirtualMachines/temurin-17.0.13/Contents/Home ./gradlew app:assembleDebug app:testDebugUnitTest
```

## Чек-лист

- [x] PERF-01 — убрать постоянную Room-подписку на каждый курс.
- [x] PERF-02 — использовать диапазонные выборки занятий.
- [x] PERF-03 — оптимизировать `synchronize()`.
- [ ] PERF-04 — ограничить множество доставленных уведомлений.
- [ ] PERF-05 — добавить индексы Room под итоговые запросы.
- [ ] PERF-06 — сделать экранные подписки lifecycle-aware.
- [ ] PERF-07 — выполнить одну итоговую проверку памяти и производительности.
- [ ] QA-UTIL-01 — исправить идемпотентное заполнение эмулятора.

## PERF-01 — убрать постоянную Room-подписку на каждый курс

### Проблема

`CoursesViewModel` хранит `progressJobs` и запускает отдельный
`observeCourseDetails(course.id)` для каждого курса. Каждый такой Flow слушает
общие invalidation-события Room. Завершённые курсы остаются в БД, поэтому число
активных collectors растёт до уничтожения `CoursesViewModel`.

### Файлы

- `tracker-app/app/src/main/java/com/utbildning/tracker/ui/courses/CoursesViewModel.kt`
- `tracker-app/app/src/main/java/com/utbildning/tracker/data/TrackerRepository.kt`
- `tracker-app/app/src/main/java/com/utbildning/tracker/data/local/TrackerDao.kt`
- при необходимости один небольшой DTO в `tracker-app/app/src/main/java/com/utbildning/tracker/data/`

### Изменения

1. Добавить единый агрегированный снимок списка курсов. Он должен содержать:
   - список курсов;
   - число завершённых и общее число тем по курсу;
   - правила расписания, сгруппированные по `courseId`;
   - число `DONE`-занятий для курсов без тем.
2. Обновлять снимок одним Flow, слушающим `courses`, `topics`,
   `schedule_rules` и `sessions`.
3. Допускается фиксированное число пакетных DAO-запросов внутри одной
   транзакции. Число Flow и запросов не должно зависеть от числа курсов.
4. В `CoursesViewModel` удалить `progressJobs`, локальные `sessions`,
   `topicProgress` и `refreshProgress()`.
5. Оставить `detailJob`: он нужен только открытому редактору курса.
6. `topicPaces` пока оставить отдельной подпиской.

### Не менять

- `observeCourseDetails(courseId)` для открытого редактора.
- Расчёт `topicPaces`.
- Внешний вид, сортировку и фильтр списка курсов.
- Редактирование, паузу и завершение курса.

### Критерии

- В `CoursesViewModel` нет `Map<String, Job>`.
- Для каждого курса не запускается отдельный collector.
- Завершённые курсы не увеличивают число постоянных подписок.
- Прогресс и расписание обновляются после изменения данных.
- Курсы без тем по-прежнему считают прогресс по `DONE`-занятиям.

### Проверка

Выполнить общую минимальную команду из начала документа один раз.

### Выполнено 2026-10-06

Список получает один `observeCoursesSnapshot()` по invalidation четырёх таблиц.
Снимок читается в одной транзакции четырьмя пакетными DAO-запросами: курсы,
агрегаты тем, агрегаты DONE-занятий и правила расписания. Число запросов
не зависит от числа курсов; полная история занятий для этого снимка не читается.
Удалены `progressJobs`, локальные `sessions`, `topicProgress` и `refreshProgress()`.
Подписка открытого редактора и `observeTopicPaces()` сохранены.

Минимальная команда под JDK 17 из `tracker-app/` прошла:
`app:assembleDebug app:testDebugUnitTest`, BUILD SUCCESSFUL, 33/33 JVM.
Схема Room и UI не менялись. Instrumentation и динамические замеры памяти
не запускались; итоговая проверка предусмотрена PERF-07.


## PERF-02 — использовать диапазонные выборки занятий

### Проблема

Today, Calendar и планировщик уведомлений получают полный список `sessions`,
хотя им нужны ограниченные диапазоны или только сигнал об изменении.

### Файлы

- `tracker-app/app/src/main/java/com/utbildning/tracker/data/local/TrackerDao.kt`
- `tracker-app/app/src/main/java/com/utbildning/tracker/data/TrackerRepository.kt`
- `tracker-app/app/src/main/java/com/utbildning/tracker/ui/today/TodayScreen.kt`
- `tracker-app/app/src/main/java/com/utbildning/tracker/ui/calendar/CalendarScreen.kt`
- `tracker-app/app/src/main/java/com/utbildning/tracker/notifications/ReminderScheduler.kt`

### Изменения

1. Добавить DAO Flow `observeSessionsBetween(firstDate, lastDate)` с
   включительными границами и прежней сортировкой `date`, `startMinute`, `id`.
2. Today должен подписываться только на сегодня и вчера. При смене локального
   дня Flow пересоздаётся с новыми границами.
3. Calendar должен подписываться только на первый и последний день выбранного
   месяца. При смене месяца Flow пересоздаётся.
4. Для `ReminderScheduler.startObserving()` добавить отдельный Flow-сигнал
   invalidation релевантных таблиц. Он возвращает `Unit` и не создаёт список
   всех `SessionEntity`.
5. Фактические кандидаты уведомлений по-прежнему читаются только через
   `getReminderCandidates()`.

### Не менять

- Срок хранения сессий.
- Правила Today/Yesterday и генерацию календаря.
- Правила уведомлений.
- `observeTopicPaces()`.

### Критерии

- Today и Calendar не вызывают общий `observeSessions()`.
- Планировщик не загружает всю историю ради invalidation-события.
- Изменение результата сразу обновляет открытый экран.
- После полуночи Today показывает новый диапазон.

### Проверка

Выполнить общую минимальную команду один раз.


### Выполнено 2026-10-06

Добавлен Room Flow с включительными границами дат и сортировкой `date`,
`startMinute`, `id`. Today подписан на сегодня/вчера с пересозданием Flow при
смене локального дня; Calendar — на выбранный месяц. Планировщик получает
`Unit` через invalidation `sessions`, `courses`, `topics`, `schedules` и
`schedule_rules`, без загрузки полной истории. Кандидаты читаются прежним
`getReminderCandidates()`. Схема Room и бизнес-правила не менялись.

Минимальная команда под JDK 17 прошла: `app:assembleDebug app:testDebugUnitTest`,
BUILD SUCCESSFUL, 33/33 JVM. Instrumentation, реальная смена дня на устройстве
и динамические замеры памяти не запускались.


## PERF-03 — оптимизировать `synchronize()`

### Проблема

Синхронизация перебирает все курсы, повторно читает один курс, загружает всю
историю занятий и обновляет старые `PENDING` по одной строке.

### Файлы

- `tracker-app/app/src/main/java/com/utbildning/tracker/data/TrackerOperations.kt`
- `tracker-app/app/src/main/java/com/utbildning/tracker/data/local/TrackerDao.kt`

### Изменения

1. Добавить DAO-запрос только для незавершённых курсов.
2. Добавить один пакетный `UPDATE sessions`, который переводит в `SKIPPED`
   строки с `result = PENDING` и `date < lastEditableDate`, одновременно меняя
   `updatedAt`.
3. Убрать `getAllSessions()` из `synchronize()`.
4. Устранить двойную генерацию: в рамках одной синхронизации `generate()`
   вызывается не более одного раза на курс.
5. Передавать уже загруженный `CourseEntity` во внутренние методы вместо
   повторного чтения по идентификатору.
6. Сохранить одну общую транзакцию.

### Не менять

- Горизонт генерации 90 дней.
- Окно изменения результата сегодня/вчера.
- Ночные занятия, паузу, завершение и исчерпание тем.
- Публичный контракт репозитория.

### Критерии

- Завершённые курсы не обходятся при синхронизации.
- Старые `PENDING` изменяются одним DAO-вызовом.
- Нет двух последовательных генераций одного курса.
- Повторная синхронизация не создаёт дубли и сохраняет `generatedThrough`.

### Проверка

```bash
/usr/bin/env JAVA_HOME=/Users/alxshvarts/Library/Java/JavaVirtualMachines/temurin-17.0.13/Contents/Home ./gradlew app:assembleDebug app:testDebugUnitTest app:assembleDebugAndroidTest
```

Instrumentation в этой задаче не запускать.

### Выполнено 2026-10-06

`synchronize()` читает только незавершённые курсы и передаёт загруженный
`CourseEntity` во внутренние методы. Проверка исчерпания тем и восстановление
курсора отделены от генерации: на курс приходится не более одного вызова
`generate()`, с единым timestamp синхронизации. Старые PENDING до вчерашней
даты переводятся в SKIPPED одним UPDATE с обновлением `updatedAt`, без чтения
`getAllSessions()`. Для защиты от дублей генерация читает только даты занятий
в своём диапазоне, вместо полной истории курса. Общая транзакция, горизонт
90 дней, окно сегодня/вчера и схема Room сохранены.

Команда под JDK 17 из `tracker-app/` прошла:
`app:assembleDebug app:testDebugUnitTest app:assembleDebugAndroidTest`,
BUILD SUCCESSFUL, 33/33 JVM. Instrumentation не запускался по границам задачи;
идемпотентность, ночные занятия и восстановление после исчерпания на устройстве
в этом шаге не проверялись. Динамические замеры предусмотрены PERF-07.

## PERF-04 — ограничить множество доставленных уведомлений

### Проблема

`SharedPreferences["delivered"]` содержит ключи всей истории уведомлений и не
очищается.

### Файл

- `tracker-app/app/src/main/java/com/utbildning/tracker/notifications/ReminderScheduler.kt`

### Изменения

1. После получения `getReminderCandidates()` сформировать допустимые ключи:
   `session.id` и `question:${session.id}`.
2. Пересечь сохранённое множество с допустимыми ключами.
3. Для расчёта следующего alarm использовать сокращённое множество.
4. Если множество изменилось, сохранить результат. Для фоновой очистки можно
   использовать `apply()`: задержка удаления старого ключа не вызывает
   повторную доставку.
5. Добавление ключей после фактической доставки оставить через `commit()` для
   долговечной дедупликации.
6. При отсутствии кандидатов очистить сохранённое множество.

### Не менять

- Типы и request code `PendingIntent`.
- Порядок start/result notification и пятиминутное окно.
- `Mutex`, атомарную проверку сессии и кнопки Done/Skipped.

### Критерии

- Размер `delivered` ограничен актуальными кандидатами.
- Доставленное уведомление не появляется повторно после `reconcile()`.
- Устаревшие ключи удаляются.
- Новые ключи по-прежнему синхронно фиксируются после доставки.

### Проверка

```bash
/usr/bin/env JAVA_HOME=/Users/alxshvarts/Library/Java/JavaVirtualMachines/temurin-17.0.13/Contents/Home ./gradlew app:assembleDebug app:testDebugUnitTest app:assembleDebugAndroidTest
```

## PERF-05 — добавить индексы Room под итоговые запросы

Выполнять после PERF-02 и PERF-03.

### Файлы

- `tracker-app/app/src/main/java/com/utbildning/tracker/data/local/Entities.kt`
- `tracker-app/app/src/main/java/com/utbildning/tracker/data/local/TrackerDatabase.kt`
- `tracker-app/app/schemas/com.utbildning.tracker.data.local.TrackerDatabase/`
- тесты миграций в `tracker-app/app/src/androidTest/java/com/utbildning/tracker/data/local/`

### Изменения

1. Добавить индекс `sessions(date, startMinute)` для диапазонных экранных
   запросов.
2. Добавить индекс `sessions(result, date)` для кандидатов уведомлений и
   пакетного закрытия старых `PENDING`.
3. Поднять версию Room с 6 до 7.
4. Добавить `MIGRATION_6_7`, создающую только эти индексы, и подключить её в
   `addMigrations()`.
5. Обновить экспортированную схему Room.
6. Добавить проверку перехода 6 → 7 в migration test.

### Не менять

- Таблицы, колонки и существующие данные.
- Partial index `index_courses_active_color`.
- Старые миграции и бизнес-логику запросов.

### Критерии

- Миграция не пересоздаёт таблицы.
- Данные курсов и занятий сохраняются.
- Оба индекса присутствуют в схеме 7.
- Новая установка и обновление с версии 6 открывают БД.

### Проверка

```bash
/usr/bin/env JAVA_HOME=/Users/alxshvarts/Library/Java/JavaVirtualMachines/temurin-17.0.13/Contents/Home ./gradlew app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.utbildning.tracker.data.local.TrackerMigrationTest
```

Полный instrumentation не запускать.

## PERF-06 — сделать экранные подписки lifecycle-aware

### Проблема

Экранные Flow и минутные циклы продолжают работать, пока композиция существует,
даже когда `Activity` находится в фоне.

### Файлы

- `tracker-app/gradle/libs.versions.toml`
- `tracker-app/app/build.gradle.kts`
- `tracker-app/app/src/main/java/com/utbildning/tracker/ui/today/TodayScreen.kt`
- `tracker-app/app/src/main/java/com/utbildning/tracker/ui/calendar/CalendarScreen.kt`
- `tracker-app/app/src/main/java/com/utbildning/tracker/ui/session/SessionScreen.kt`
- `tracker-app/app/src/main/java/com/utbildning/tracker/ui/schedule/ScheduleScreen.kt`

### Изменения

1. Добавить `androidx.lifecycle:lifecycle-runtime-compose` той же версии
   lifecycle, которая уже используется проектом.
2. Заменить экранные `collectAsState()` на `collectAsStateWithLifecycle()`.
3. Минутные циклы Today и Calendar выполнять внутри
   `repeatOnLifecycle(Lifecycle.State.STARTED)`.
4. При каждом новом входе в `STARTED` сначала сразу обновлять дату/время, затем
   ждать следующего обновления.

### Не менять

- Процессную подписку `ReminderScheduler`.
- `SynchronizeOnResume`.
- Частоту обновления активного экрана.
- Расчёт локального дня и часового пояса.

### Критерии

- Ниже `STARTED` экранные Flow и минутные циклы не работают.
- После возврата экран сразу показывает актуальную дату.
- Поворот экрана не создаёт второй параллельный таймер.

### Проверка

Выполнить общую минимальную команду один раз.

## PERF-07 — итоговая проверка памяти и производительности

Выполнять после PERF-01–PERF-06 и QA-UTIL-01. Код приложения в этой задаче не
изменять.

### Результаты

Сохранить:

- `docs/checks/memory-performance/README.md`
- диагностические файлы в `docs/checks/memory-performance/logs/`

### Проверка

1. Собрать и установить APK поверх существующего без очистки данных.
2. Один раз выполнить идемпотентное заполнение.
3. Один раз запустить `AppNavigationTest`; полный instrumentation не запускать.
4. Снять исходный `dumpsys meminfo`.
5. Выполнить 20 recreation `Activity` поворотами экрана.
6. Сделать heap dump для принудительной GC и снять итоговый `dumpsys meminfo`.
7. Сравнить `Activities`, `ViewRootImpl`, `AppContexts`, Java Heap и SQLite
   cache hits.
8. Восстановить исходную настройку автоматического поворота.

### Критерии

- После GC остаётся одна актуальная `Activity` и один `ViewRootImpl`.
- Нет продолжающегося роста после прекращения навигации и GC.
- В отчёте приведены исходные и итоговые значения и ограничения проверки.
- Пользовательский AVD и данные приложения не очищались.

## QA-UTIL-01 — исправить идемпотентное заполнение эмулятора

### Проблема

`EmulatorDemoSeedTest` вызывает `first { it !in used }` и падает, если все
десять цветов заняты.

### Файл

- `tracker-app/app/src/androidTest/java/com/utbildning/tracker/ui/EmulatorDemoSeedTest.kt`

### Изменения

1. В основном цикле создания demo-курсов использовать безопасный выбор:

   ```kotlin
   val color = (0..9).firstOrNull { it !in used } ?: continue
   ```

2. В `seedToday` не использовать `checkNotNull()` для fixture, который мог быть
   пропущен из-за занятой палитры.
3. При отсутствии fixture пропускать только его, продолжая заполнение остальных.
4. Не удалять, не завершать и не переписывать пользовательские курсы и
   результаты.

### Критерии

- Скрипт не падает при десяти занятых цветах.
- Повторный запуск не создаёт дубли.
- Существующие данные сохраняются.
- Отсутствие свободного цвета считается допустимым пропуском fixture.

### Проверка

```bash
sh scripts/seed-emulator.sh
```

Полный instrumentation после этого не запускать.
