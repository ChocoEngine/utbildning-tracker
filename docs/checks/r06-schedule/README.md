# R06 — разделение даты завершения и времени

2026-10-18, ветка `codex/version-02`.

По последнему замечанию пользователя:

- Разделитель после конечной даты: 2 dp, цвет Material `outline` вместо более светлого стандартного разделителя.
- Перед днями недели добавлен заголовок «Время» / `Time` в том же `titleMedium`, что «Дата завершения курса».
- Блок кнопок времени приближен к строке дня на 12 dp. Для однострочного времени обычный видимый зазор уменьшен примерно вдвое; при переносе окончания на узком экране высота строки по-прежнему зависит от текста. Минимальные размеры кнопок не уменьшены. Разделители дней остаются после всего блока.

## Проверки

JDK 17, отдельный `ScheduleReturnFix`, Android 16/API 36. Пользовательский AVD не запускался. Повторяемое демо-наполнение выполнено через существующий `scripts/seed-emulator.sh`, без очистки данных.

Из `tracker-app/`:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest --console=plain
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.utbildning.tracker.ui.ScheduleScreenTest,com.utbildning.tracker.ui.ScheduleOverlapReviewTest --console=plain
adb -s emulator-5554 shell am instrument -w -e class com.utbildning.tracker.ui.ScheduleOverlapReviewTest com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
```

Сборка debug/androidTest успешна, JVM 36/36, lint — 0 ошибок / 46 предупреждений. Направленный instrumentation — 13/13, без пропусков и падений; [XML](instrumentation.xml). Дополнительный запуск визуального класса для сохранения снимков — 2/2.

Просмотрены четыре снимка RU/EN, 320 × 540 dp: выбранный день с окончанием и с кнопкой его добавления. Заголовки и разделитель различимы; тексты, предупреждения и кнопка сохранения не перекрываются. Проверки выбора/удаления даты, изменения времени, read-only и сохранения черновика входят в направленный набор.

| Состояние | RU | EN |
|---|---|---|
| С окончанием | [Снимок](screenshots/schedule-overlaps/ru_0.png) | [Снимок](screenshots/schedule-overlaps/en_0.png) |
| Без окончания | [Снимок](screenshots/schedule-overlaps/ru_2.png) | [Снимок](screenshots/schedule-overlaps/en_2.png) |

APK: `tracker-app/app/build/outputs/apk/debug/app-debug.apk`, versionName `0.1.0`, versionCode `1`.

Полный instrumentation, Android 13 и физическое устройство в этой задаче не проверялись. Окончательная пользовательская приёмка R06 остаётся открытой; R07 не начинался.

## Дополнение — запрет прошлой конечной даты

Календарь использует `SelectableDates`: дни раньше сегодняшнего местного дня и
прошлые годы недоступны. Подтверждение требует выбранной даты не раньше сегодня;
проверка защищает также ручной ввод и ранее сохранённую прошлую дату. Проверка
при сохранении всей формы сохранена. Дата календаря декодируется как UTC-день,
а сравнивается с локальным сегодняшним днём, как и остальная форма.

Новая UI-регрессия проверила блокировку старой даты и сохранения формы,
недоступность вчера, успешный выбор и сохранение сегодня и завтра.
Сборка debug/androidTest, lint (0 ошибок / 46 предупреждений), JVM 36/36 и
`ScheduleScreenTest` 12/12 на отдельном API 36 AVD прошли.
[XML нового прогона](date-limit-instrumentation.xml).
Команда — та же сборка выше, для instrumentation выбран только
`-Pandroid.testInstrumentationRunnerArguments.class=com.utbildning.tracker.ui.ScheduleScreenTest`.
Первый запуск новой регрессии упал на поиске числа дня: Material сообщает полную
дату в semantics. Селектор исправлен на полную semantics-строку с числом и действием;
ограничение приложения не менялось между этими прогонами.
Ручной ввод отдельно не воспроизводился; полный набор и Android 13 не запускались.
APK по указанному выше пути обновлён.
