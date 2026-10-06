# Завершение R03 — 2026-10-06

R03 закрыт в проверенном объёме API 36 с учётом прежних системных результатов
API 33. Новых подтверждённых дефектов приложения в оставшихся сценариях не найдено.

## Объём и окружение

Дополнены оставшиеся системные проверки дат и действий уведомлений. Код приложения
и схема Room не менялись. Добавлены `OpenMidnightTest` и
`NotificationShadeResultTest`; прежние несвязанные изменения и логи QA-001 сохранены.

Использован отдельный API 36 AVD `ScheduleReturnFix`, `emulator-5558`,
Europe/Moscow, 320 × 540 dp, с существующей демонстрационной БД. Оба APK
установлены через `adb -s emulator-5558 install -r`, без очистки приложения.
Временные курсы тестов удалены в `finally`; пользовательский AVD не затронут.
Для полуночи только часы тестового AVD переведены на 2026-10-06 23:59:15
через `cmd alarm set-time 1791320355000`, затем возвращены к текущему времени.
Наступление полуночи было естественным, без посылки искусственного broadcast.

## Новые фактические проверки

- Настоящая открытая `MainActivity` пересекла полночь без pause/resume, recreation
  или перезапуска. Системное дерево UI подтвердило перенос даты из «Сегодня»
  во «Вчера». Room сохранил вчерашнее `PENDING`, перевёл более старое занятие
  в `SKIPPED`; дублей и автоматического завершения курса нет.
  [Runner: 1/1, 41.160 s](midnight-runner.txt).
- В системной шторке реально нажаты обе кнопки через `input tap` по координатам
  узлов SystemUI: RU/EN × сегодня/вчера × с темами/без тем × DONE/SKIPPED,
  **16 комбинаций**, объединённых в один тест. Для курса с темами DONE открывает
  выбор: до подтверждения результат остаётся PENDING, после выбора первой темы
  сохраняются DONE и `completionDate` даты начала, вторая тема не закрывается.
  SKIPPED не меняет темы. Без тем DONE записывается через Activity сразу;
  SKIPPED обрабатывается receiver. Обработанные уведомления исчезают.
  [Runner: 1/1, 19.142 s](shade-runner.txt).

Обе новые системные проверки работают без Compose test clock: реальное ожидание
RTC и работа SystemUI не подменяются продвижением виртуального времени.
При разработке проб обнаружены ограничения самой проверки: удалённый поиск текста
SystemUI, переходные координаты карточки, асинхронная отмена уведомления и виртуальные
часы Compose. В итоговом тесте дерево обходится явно, карточка раскрывается,
ожидается завершение анимации/отмены. Первоначальные неудачные пробы не являются
подтверждёнными дефектами приложения.

## Покрытие критериев R03

| Критерий | Доказательство |
|---|---|
| Долгое отсутствие, горизонт 90 дней, повторная генерация, конец расписания | `ScheduleRepositoryTest`: longAbsence…, absenceBeyondInitialHorizon…, initialGeneration…, generationDoesNotAdd…; конечная дата не завершает курс |
| Смена суток при открытом приложении | Новая настоящая Activity-проверка выше; D/D+1/D+2 дополнительно покрыты репозиторием |
| Месяц/год, високосный день, пояс, DST gap/overlap, сутки 23/25 часов | `ScheduleGeneratorTest`, `LocalDayTest`, `SessionResultRepositoryTest.editableDatesFollowTheCurrentZoneAndCrossYearBoundary`, `CalendarTodayScreenTest` |
| Начало и вопрос, подавление позднего начала, повторная доставка | `ReminderTest.endReminderAsksForResultAndRecordsBothEvents`, `ReminderNavigationTest` |
| Отмена после изменения/отметки/паузы/завершения/удаления/исчерпания | `ReminderTest`: dueRemindersRespect…, reconciliationKeeps…, futureSessionRegisters… |
| Переход в нужное занятие, тело не записывает результат, выбор тем, оба исхода | `ReminderNavigationTest`, `SessionScreenTest`, новая матрица системной шторки |
| Старые/повторные действия, гонки, отказ и повтор записи | `SessionResultRepositoryTest`, `ReminderTest.staleRepeated…`, `writeFailure…` |
| Реальные ночные question alarm 23:30→01:00 и +90 минут | Прежний [RTC-прогон API 36](../repeat-review-fixes/README.md), 1/1, 67.919 s; синхронизация до/после показа, оба результата в Room |
| Настоящий reboot и отзыв/возврат обоих разрешений | Прежние [системные прогоны API 36 и API 33](../repeat-review-fixes/README.md): восстановление alarm, доставка, сохранение данных |

Предыдущие дорогие ночные/reboot/permissions проверки учитываются по своим отчётам,
как разрешает критерий R03; в этой задаче они заново не выполнялись. Проверки пояса
и DST используют фиксированные часы и зоны, а не фактический сезонный перевод
часов устройства. Регистрация будущего alarm остаётся проверкой регистрации,
а не ожиданием доставки завтра.

## Команды и результаты

Из `tracker-app/`, с JDK 17 и Android SDK из `README.md`:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
./gradlew :app:testDebugUnitTest --rerun-tasks :app:lintDebug --console=plain
```

Сборка успешна ([лог](build.txt)), свежий JVM-прогон **33/33**
([сводка XML](jvm-summary.txt)), lint **0 ошибок / 35 предупреждений**.

Целевой instrumentation **56/56**, без failures и assumption-пропусков,
169.739 s ([runner](focused-runner.txt)). Включены `ScheduleRepositoryTest`,
`SessionResultRepositoryTest`, `ReminderTest`, `ReminderNavigationTest`,
`LocalizedReminderDeliveryTest`, `CalendarTodayScreenTest`, `SessionScreenTest`.
Обычная реальная exact-alarm доставка, внешний переход и отдельные RU/EN-доставки
в этом прогоне выполнены заново. Вместе с двумя отдельными системными тестами
получено **58/58** instrumentation-проверок; 16 комбинаций шторки входят в один тест.

```sh
adb -s emulator-5558 shell am instrument -w -r -e class com.utbildning.tracker.data.ScheduleRepositoryTest,com.utbildning.tracker.data.SessionResultRepositoryTest,com.utbildning.tracker.notifications.ReminderTest,com.utbildning.tracker.notifications.ReminderNavigationTest,com.utbildning.tracker.notifications.LocalizedReminderDeliveryTest,com.utbildning.tracker.ui.CalendarTodayScreenTest,com.utbildning.tracker.ui.SessionScreenTest com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
```

Системные проверки запускаются отдельными командами:

```sh
adb -s emulator-5558 shell am instrument -w -r -e class com.utbildning.tracker.ui.OpenMidnightTest com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5558 shell am instrument -w -r -e class com.utbildning.tracker.notifications.NotificationShadeResultTest com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
```

Первой команде требуется отдельный AVD в 23:59:00–23:59:44; вне окна тест
пропускается по assumption. Матрица шторки требует стабильного времени вне
двух минут у полуночи. Во время фактических запусков обе проверки выполнены,
assumption-пропусков не было.

## Границы результата

Полный instrumentation и три последовательных прогона QA-001 в эту задачу
не входят; прежний полный прогон с RU IME failure не объявляется зелёным.
Окно категории UI-C01 остаётся отдельной отложенной задачей.
Android 13 в этой задаче не запускался: обычные alarm/reboot/permissions ранее
прошли на API 33, отдельное ночное ожидание и действия шторки на нём не повторялись.
Проверка на физическом устройстве остаётся параллельным R06. Коммит/push не выполнялись.
