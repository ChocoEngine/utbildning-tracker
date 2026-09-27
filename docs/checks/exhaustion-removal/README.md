# Удаление exhaustedAt — 2026-09-27

Схема Room v6, миграции 1→2→3→4→5→6. Исторические экспорты и миграции сохранены.
Исчерпание вычисляется по темам: есть хотя бы одна тема, непройденных нет.
Генерация и напоминания используют это правило. Курсы без тем продолжают занятия.
Наблюдение напоминаний реагирует на изменения topics, courses и sessions.

При исчерпании удаляются будущие PENDING; начавшиеся занятия и записанные результаты
сохраняются. generatedThrough сбрасывается до вчерашнего дня, поэтому после снятия
отметки или добавления темы занятия генерируются с текущего момента, в том числе
после повторного открытия БД. Задним числом новые занятия не создаются.

Миграция 5→6 пересоздаёт таблицы с сохранением категорий, курсов, тем, расписаний,
правил и занятий; проверены внешние ключи и сохранение частичного индекса цветов.
Старая граница генерации сбрасывается для курсов с exhaustedAt и для непустого
полностью пройденного списка, даже если старое поле было NULL.
Диаграмма docs/diagrams/database.html сгенерирована из 6.json; состав таблиц и полей
сверен с экспортом Room.

## Проверки

- assembleDebug, assembleDebugAndroidTest, testDebugUnitTest, lintDebug: BUILD SUCCESSFUL.
- JVM: 15 тестов, 0 ошибок/пропусков.
- lint: 0 ошибок / 27 предупреждений.
- API 36, пакет data: OK (65 tests), включая миграции 1→6, 3→6, 4→6, 5→6,
  остановку/возобновление, повторное открытие БД и курс без тем.
- Первый прогон data: 64 успешных, одно устаревшее ожидание generatedThrough в
  миграции 1→6 исправлено; весь пакет повторно прошёл.
- API 36: ReminderTest, ReminderNavigationTest, CalendarIntegrationTest, CoursesScreenTest — OK (16 tests); реальная доставка AlarmManager и переход из уведомления также прошли.
- Эмулятор обновлён через adb install -r и seed-emulator.sh: данные и прогресс
  сохраняются; повторяемое заполнение успешно (1 test).

Команда сборки (из tracker-app, JDK 17 и Android SDK из README):

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
```

Команды инструментальных проверок после установки обоих APK через adb install -r:

```sh
adb shell am instrument -w -e package com.utbildning.tracker.data com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.utbildning.tracker.notifications.ReminderTest,com.utbildning.tracker.notifications.ReminderNavigationTest,com.utbildning.tracker.ui.CalendarIntegrationTest,com.utbildning.tracker.ui.CoursesScreenTest com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
```

Android 13, полный повторный UI-прогон и ручная визуальная проверка в этой задаче
не выполнялись. Ранее зафиксированные результаты других изменений остаются
отдельными контрольными точками.
