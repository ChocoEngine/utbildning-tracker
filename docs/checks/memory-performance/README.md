# PERF-07 — итоговая проверка памяти, 2026-10-07

После 20 пересозданий Activity поворотами и принудительной GC остаются одна
Activity и один ViewRootImpl. В двух последовательных снимках после GC роста
Java Heap/AppContexts не наблюдалось. Направленная навигация не зелёная: 2/7,
пять failures на заполненной палитре. Код приложения и тестов не менялся.

## Среда и действия

Отдельный ScheduleReturnFix API 36 AVD, emulator-5554. Пользовательский AVD
не запускался и не очищался; данные отдельного AVD также не очищались.
APK и androidTest собраны под JDK 17 (`app:assembleDebug
app:assembleDebugAndroidTest`, BUILD SUCCESSFUL), установлены через install -r.
`sh scripts/seed-emulator.sh` выполнен один раз успешно: seedDemoCourses,
keepThreeDemoCourses пропущен по opt-in assumption. Полный instrumentation не
запускался. AppNavigationTest выполнен ровно один раз через am instrument.

Приложение открыто на MainActivity. Снята исходная память; автоматический
поворот отключён, user_rotation переключён 0→1→0 двадцать раз с паузой
1,5 секунды. Каждый dumpsys activity top содержит новый экземпляр MainActivity:
20 снимков / 20 уникальных identity, pid 4762 сохранялся. Системные события
relaunch/destroy/create также сохранены. Промежуточные экземпляры до GC не
интерпретируются как утечка.

Сделаны два `am dumpheap com.utbildning.tracker /data/local/tmp/perf07-*.hprof`
без параметра -g (обычный managed dump с GC); после каждого выдержано 5 секунд,
между проверками ещё 10 секунд без навигации. Файлы heap dump оставлены на
отдельном AVD, их размеры сохранены в логах; бинарные дампы в репозиторий не
добавлены. Исходные и восстановленные настройки совпадают:
accelerometer_rotation=1, user_rotation=0, восстановление выполнено в finally.

## Значения

Память в КБ, Java Heap — PSS из App Summary. SQLite hits — накопительный
счётчик POOL STATS, а не оценка задержки запроса.

| Метрика | Исходно | После 20 поворотов | После GC | Пауза и повторная GC |
|---|---:|---:|---:|---:|
| Activities | 1 | 3 | 1 | 1 |
| ViewRootImpl | 1 | 2 | 1 | 1 |
| AppContexts | 6 | 9 | 5 | 5 |
| Java Heap PSS | 16196 | 20204 | 17300 | 16160 |
| Dalvik Heap Alloc | 5268 | — | — | 5248 |
| TOTAL PSS | 89045 | 102636 | 146694 | 141952 |
| SQLite pool cache hits | 52 | 1012 | 1012 | 1012 |
| SQLite pool cache misses | 104 | 272 | 280 | 288 |

SQL MEMORY_USED оставался 392 КБ, PAGECACHE_OVERFLOW 220 КБ. После dumpheap
TOTAL PSS выше исходного: в последнем снимке Native Heap PSS 56875 КБ против
10553 КБ исходно, при этом Native Heap Alloc 18250 против 18168 КБ. Это
наблюдение не доказывает причину роста PSS. Между двумя финальными снимками
TOTAL PSS снизился на 4742 КБ, Java Heap — на 1140 КБ. Длительная стабильность
общего PSS и отсутствие других типов утечек этим коротким опытом не доказаны.

## Направленная навигация и ограничения

Runner сообщает Tests run: 7, Failures: 5; exit code adb=0 не считается успехом.
Четыре теста падают на `availableColors().first()` с List is empty:
courseEditorHidesTabsAndReturningToListRestoresThem,
unscheduledCourseCanCancelEnableAndEditThroughPencil,
pausedCourseCanResumeThroughScheduleWithoutLosingProgress,
existingScheduledCourseWithoutScheduleCanBeConfigured.
newScheduledCourseCanBeConfiguredThroughCourseEditorAndCreatesCalendarSessions
ожидает отсутствующий course_add на полностью занятой палитре. Точное место —
AppNavigationTest.kt:85, четыре других места — в стеке navigation.txt.
Существующие данные не удалялись ради освобождения цветов; повторного прогона
и исправления тестов в этой задаче не было. Это ограничение набора фикстур,
а не подтверждённая регрессия PERF-01–06.

Проверка удержания Activity прошла, весь набор проверок не зелёный из-за
навигации. Сравнения со старой версией на одинаковом наборе данных, измерений
latency/CPU, полного instrumentation, API 33 и физического устройства не было.
Heap dump не анализировался по dominator tree. Итог не распространяется на
все сценарии приложения или длительный фон.

Диагностика: [logs](logs/), meminfo-before/after-rotations/gc/settled-gc,
rotation-original/restored, recreation-summary, rotation-01…20,
activity-events, navigation, seed и сведения о heap dump. В rotation-*.txt
сохранён только блок целевого приложения, без диагностики launcher.
