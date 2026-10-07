# F16 — ежедневная автоматическая очистка

Проверено 2026-10-07.

## Реализовано

- `TrackerRepository.cleanupOldSessions()` выполняет одну Room-транзакцию,
  внутри которой повторно читает текущую `RetentionPolicy`, определяет локальную
  дату через внедрённые часы и часовой пояс и удаляет одним DAO-запросом только
  `sessions.date < today - N`.
- Отключённая политика ничего не удаляет. День на границе остаётся.
- `CalendarCleanupWorker` запускает операцию; отмена корутины передаётся дальше,
  прочая ошибка возвращает `Result.retry()`.
- `CalendarCleanupManager` поддерживает одну уникальную периодическую задачу
  `daily-calendar-cleanup` с интервалом один день и политикой `KEEP`.
  Reconcile выполняется при старте `MainActivity`. Точный час запуска не
  гарантируется.
- Схема Room не менялась. Очистка не обращается к таблицам курсов, категорий,
  тем, расписаний и `backup_state`; будущие занятия остаются в БД и продолжают
  участвовать в обычной сверке напоминаний.

## Проверки

Из `tracker-app/`:

```text
./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:lintDebug
BUILD SUCCESSFUL
36/36 JVM tests passed

./gradlew :app:assembleDebugAndroidTest
BUILD SUCCESSFUL

sh scripts/seed-emulator.sh
OK (2 tests), повторяемое заполнение отдельного API 36 AVD

adb shell am instrument -w -e class com.utbildning.tracker.maintenance.CalendarCleanupTest com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
OK (4 tests)

adb shell am instrument -w -e class com.utbildning.tracker.maintenance.CalendarCleanupTest,com.utbildning.tracker.ui.RetentionSettingsTest com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
OK (6 tests), финальный совместный прогон очистки и настройки RU/EN
```

Направленные тесты подтвердили:

- одинаковое удаление для активного, приостановленного и завершённого курса;
- сохранение занятия на границе и будущего занятия, курсов, тем с прохождением,
  расписаний с правилами и внутреннего состояния последней восстановленной копии;
- идемпотентный повтор, два параллельных вызова и применение уменьшенного срока
  на следующем запуске;
- полное отсутствие удаления при отключённой политике;
- откат всего DELETE при внедрённой SQLite-ошибке;
- одну активную уникальную периодическую WorkManager-задачу после повторного
  reconcile и правильный класс worker.

## Не проверено

- полный instrumentation-набор;
- Android 13;
- фактическое ожидание суток и задержка запуска со стороны ОС;
- принудительное завершение процесса ровно во время DELETE. Атомарность этого
  случая обеспечивается одной SQLite-транзакцией и одним DELETE, но отдельно на
  устройстве не моделировалась.
