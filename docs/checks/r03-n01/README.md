# R03-N01 — окно PENDING и ночные уведомления

Проверено 2026-10-05.

## Реализация

- Догоняющая синхронизация переводит в `SKIPPED` только `PENDING` с датой начала строго раньше вчера; вчерашнее занятие остаётся редактируемым.
- Календарь вычисляемо показывает прошлое `PENDING` как пропуск для подписи, кляксы/полосы, крестика и accessibility, не изменяя объект или БД. Сегодня и будущее остаются «В плане».
- Календарная дата обновляется на местной границе суток; расчёт использует текущий системный часовой пояс. Репозиторий и уведомления продолжают работать с фактическим результатом.
- Ночной вопрос в окончание или через 90 минут остаётся допустимым для вчерашнего `PENDING`; атомарная проверка действия отклоняет позавчерашние, будущие, завершённые и уже отмеченные занятия.

## Проверки

Из `tracker-app/` с JDK 17 и Android SDK 36:

```text
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL; lint: 0 ошибок, 33 предупреждения.
```

На `emulator-5554`, API 36, с явным serial и установкой `-r` без очистки пользовательских данных:

- Все 42 теста в `SessionResultRepositoryTest`, `ScheduleRepositoryTest`, `CourseLifecycleRepositoryTest`, `CalendarTodayScreenTest`, `CalendarIntegrationTest`, `ReminderTest`, `ReminderNavigationTest` в итоге прошли. В общем запуске `CalendarIntegrationTest` один раз упал при teardown внутри Compose `SlotWriter`, затем отдельно прошёл 1/1; не успевшие выполниться классы повторены ниже.
- Повторно `CalendarTodayScreenTest`, `ReminderTest`, `ReminderNavigationTest`: 10/10.
- `VisualReviewTest` и `TodayVisualReviewTest`: 4/4; сохранены RU/EN-снимки 320 dp.
- `ReminderNavigationTest` реально дождался обычного exact AlarmManager-события и проверил однократную доставку/переход. Это не ночной сценарий.

## Снимки

- [Календарь, RU](screenshots/ru_calendar_320dp.png)
- [Календарь, EN](screenshots/en_calendar_320dp.png)
- [Вчерашнее PENDING, RU](screenshots/today_ru_yesterday.png)
- [Вчерашнее PENDING, EN](screenshots/today_en_yesterday.png)

## Ограничения

- Не выполнено реальное ожидание вопроса после полуночи с синхронизацией до/после показа и нажатием обеих кнопок в системной шторке на отдельных занятиях; время единственного доступного AVD не менялось.
- Отдельного второго AVD в окружении не было: использован доступный `Medium_Phone_API_36` (`emulator-5554`) без очистки данных.
- Android 13, reboot, смена/отзыв разрешений и полный instrumentation-набор не проверялись.
- R03 целиком не закрыт.
