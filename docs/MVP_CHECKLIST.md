# Сквозная проверка MVP — S22

Соответствие 14 сценариям раздела 10 [MVP_PLAN.md](../MVP_PLAN.md).
Общий прогон 2026-09-26: BUILD SUCCESSFUL, 13 JVM + 100 инструментальных
тестов на Android 16 / API 36; включая `MvpScenarioTest`. Фактические
системные проверки и ограничения — [отчёт](checks/mvp/README.md).
Таблица различает автоматизированные и отдельные системные свидетельства.
S22/S23 приняты на API 36; Android 13 остаётся отложенным в S01/S20.

| № | Сценарий | Автоматизированное свидетельство / граница проверки |
|---|---|---|
| 1 | Категория, лекции с тремя темами, практика без тем | [MvpScenarioTest][mvp] создаёт четыре курса в одной категории; [CourseFormRepositoryTest][form] проверяет сохранение формы; [CoursesScreenTest][courses] — интерфейс формы. |
| 2 | Разные дни/время и общий календарь | [MvpScenarioTest][mvp] задаёт разные правила; [ScheduleScreenTest][schedule-ui] проверяет форму; [CalendarIntegrationTest][calendar] — пополнение дальнего месяца и действия с результатом. |
| 3 | Несколько тем за занятие, пропуск и исправление без двойного учёта | [MvpScenarioTest][mvp], [SessionResultRepositoryTest][results] и [SessionScreenTest][result-ui]. Темы выбираются при результате, не назначаются генератором. |
| 4 | Полночь, догоняющие пропуски, отсутствие старого вопроса | [ScheduleRepositoryTest][schedule] — полночь и отсутствие дольше горизонта; [CalendarTodayScreenTest][today] — отображение; [CalendarIntegrationTest][calendar] — исправление из календаря. |
| 5 | Вопрос после окончания или через 90 минут | [ScheduleGeneratorTest][generator] — время, ночные интервалы/DST; [CalendarTodayScreenTest][today] — появление карточки. |
| 6 | Последняя тема, завершить или оставить активным | [CourseLifecycleRepositoryTest][lifecycle] — оба состояния, остановка, отказ и новый цикл; [CourseLifecycleScreenTest][lifecycle-ui] — подтверждения и отказ. |
| 7 | Ручное завершение, история и отмена напоминания | [MvpScenarioTest][mvp] и [CourseLifecycleRepositoryTest][lifecycle] сохраняют историю; [ReminderTest][reminder] проверяет отсутствие доставки уже наступившего отменённого напоминания. |
| 8 | Конечная дата, бессрочное пополнение без дублей | [ScheduleRepositoryTest][schedule] и [ScheduleGeneratorTest][generator]. Конечная дата сама курс не завершает. |
| 9 | Перезапуск приложения и устройства, данные и уведомления | [MvpScenarioTest][mvp] закрывает/открывает файловую БД; [CourseLifecycleRepositoryTest][lifecycle] проверяет сохранение остановки; [LanguageSettingsTest][language] — перезапуск Activity. Реальная перезагрузка API 36 и восстановление alarm проверены отдельно, результат в отчёте. |
| 10 | Без сети и без разрешения уведомлений | Полный прогон выполнен с отключёнными Wi-Fi/мобильными данными; затем отдельный RecoveryProbeTest и холодный запуск Activity прошли при реальном отзыве POST_NOTIFICATIONS, ID занятий сохранились. [Свидетельство](checks/mvp/offline-checks.txt). Имитация отказа через app-op не считается доказательством: на API 36 она не изменила `allowed`. Отзыв разрешения делать вне instrumentation, он может завершить процесс. |
| 11 | Двадцать тем без расписания, отметки не по порядку, снятие, сохранение | [MvpScenarioTest][mvp] выполняет этот сценарий с файловой БД; [ManualTopicCompletionTest][manual] проверяет источники отметок; [CoursesScreenTest][courses] — жесты. |
| 12 | Без календаря/уведомлений для свободного курса, завершение | [MvpScenarioTest][mvp] и [ManualTopicCompletionTest][manual] проверяют отсутствие занятий и расписания; [CourseLifecycleScreenTest][lifecycle-ui] и [CourseLifecycleRepositoryTest][lifecycle] — предложение и завершение. |
| 13 | Домашка по C: 40 задач, 4 за первое занятие, 0 за второе, исправление; две лекции за раз | [MvpScenarioTest][mvp] проверяет ровно 4/40 после двух DONE, затем выбор не по порядку, исправление без дублей и две лекции за раз. Это проверка данных; яркость/полосы календаря проверяются отдельно визуально и в [CalendarTodayScreenTest][today]. |
| 14 | RU/EN, системный/неподдерживаемый язык, даты, множественные формы, уведомления и обрезания | [LanguageSettingsTest][language] проверяет выбор/перезапуск/fallback и ресурсы навигации. [VisualReviewTest][visual] подготавливает экраны для визуальной проверки. [LocalizedReminderDeliveryTest][localized-reminder] проверяет реальные уведомления RU/EN и канал. [LanguageSettingsTest][language] проверяет неизменность названий, расписания, занятий и прогресса при смене языка. Снимки RU/EN просмотрены, ограничения размеров — в отчёте. |

## Дополнительные ограничения

- Лимит 10, уникальные цвета, конкурентное сохранение и откат: [TrackerRepositoryTest][repository].
- Удаление категории, включая завершённые курсы, сохраняет данные: [CourseFormRepositoryTest][form].
- Пауза сохраняет цвет и историю, завершение освобождает цвет, удаление каскадно очищает курс: [CourseLifecycleRepositoryTest][lifecycle].
- Редактор списка, конфликты с пройденными темами, повторяющиеся названия и стабильные ID: [TopicListRepositoryTest][topics] и [MvpScenarioTest][mvp].
- Короткое/долгое нажатие, отмена жеста прокруткой: [CoursesScreenTest][courses].
- Миграция существующей БД: [TrackerMigrationTest][migration], схема 1→2 без удаления данных.
- Реальный AlarmManager, открытие занятия из уведомления и отсутствие повторной доставки: [ReminderNavigationTest][reminder-nav]. Устаревшие события после изменения курса: [ReminderTest][reminder]. Тесты выдают разрешения через shell после установки UTP; не отзывают их посреди процесса.

## Результат итоговой проверки

- Итоговая сборка/JVM/instrumentation: BUILD SUCCESSFUL, 13 JVM + 100 инструментальных, без ошибок и пропусков.
- Android 16 / API 36: полный прогон выполнен на Medium_Phone_API_36.
- Реальная перезагрузка, смена часов/пояса и отзыв POST_NOTIFICATIONS проверены на API 36; подробности в отчёте. Работа без сети подтверждена полным прогоном и отдельным denied-probe/запуском UI.
- Android 13 / API 33: отложен пользователем, не считать проверенным.
- Сохранены 22 снимка RU/EN при 320 dp; просмотренные экраны без наложений/горизонтального обрезания. Реальные уведомления RU/EN проверены на устройстве; гайд прочитан и повторно открыт через UI на обоих языках.

[mvp]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/data/MvpScenarioTest.kt
[form]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/data/CourseFormRepositoryTest.kt
[courses]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/ui/CoursesScreenTest.kt
[schedule-ui]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/ui/ScheduleScreenTest.kt
[calendar]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/ui/CalendarIntegrationTest.kt
[results]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/data/SessionResultRepositoryTest.kt
[result-ui]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/ui/SessionScreenTest.kt
[schedule]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/data/ScheduleRepositoryTest.kt
[today]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/ui/CalendarTodayScreenTest.kt
[generator]: ../tracker-app/app/src/test/java/com/utbildning/tracker/domain/ScheduleGeneratorTest.kt
[lifecycle]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/data/CourseLifecycleRepositoryTest.kt
[lifecycle-ui]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/ui/CourseLifecycleScreenTest.kt
[reminder]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/notifications/ReminderTest.kt
[reminder-nav]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/notifications/ReminderNavigationTest.kt
[language]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/ui/LanguageSettingsTest.kt
[manual]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/data/ManualTopicCompletionTest.kt
[visual]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/ui/VisualReviewTest.kt
[repository]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/data/TrackerRepositoryTest.kt
[topics]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/data/TopicListRepositoryTest.kt
[migration]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/data/local/TrackerMigrationTest.kt

[localized-reminder]: ../tracker-app/app/src/androidTest/java/com/utbildning/tracker/notifications/LocalizedReminderDeliveryTest.kt
