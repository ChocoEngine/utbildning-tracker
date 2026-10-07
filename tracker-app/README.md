# Android-приложение

Открывать эту папку (`tracker-app/`) в Android Studio. Один модуль `app`,
namespace и applicationId — `com.utbildning.tracker`.
Реализованы курсы и категории, редактор тем, расписания, календарь,
экран «Сегодня», результаты занятий, прогресс, завершение/пауза/удаление
и локальные напоминания. Выбор RU/EN сохраняет системный LocaleManager.
Все Compose-экраны имеют автономные Preview в теме приложения.

Room хранит данные локально; схема v8 и миграции 1→2→3→4→5→6→7→8 находятся в `app/schemas/`
и `data/local/TrackerDatabase.kt`. Контракты: [данные](../docs/DATA_CONTRACT.md),
[расписание](../docs/SCHEDULE_CONTRACT.md), [уведомления](../docs/NOTIFICATIONS.md).
Текущий статус функционального расширения и оставшиеся ограничения —
[отчёт F17](../docs/checks/f17/README.md) и [план](../FEATURE_PLAN.md).
Android 13 отложен пользователем; приёмка S25 остаётся отдельной.

## Закреплённые инструменты

| Инструмент | Версия |
|---|---|
| JDK / JVM target | 17 |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin и Compose compiler plugin | 2.3.10 |
| Compose BOM | 2025.12.00 |
| Activity Compose | 1.12.2 |
| Navigation Compose | 2.9.6 |
| Room / compiler / Gradle plugin | 2.8.4 |
| KSP | 2.3.6 |
| AndroidX Test runner / ext JUnit | 1.6.2 / 1.2.1 |
| minSdk | 33 (Android 13) |
| compileSdk / targetSdk | 36 (Android 16) |
| SDK Build Tools (по умолчанию AGP) | 35.0.0 |

Это стабильный закреплённый набор, не требование использовать самые новые версии.
Совместимость AGP/Gradle/JDK/API проверена по
[документации AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes).
Совместимость Kotlin с AGP и Gradle — по
[таблице Kotlin Gradle plugin](https://kotlinlang.org/docs/gradle-configure-project.html).
Версия Compose compiler совпадает с Kotlin согласно
[инструкции настройки](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler).
Compose BOM взят из
[стабильного выпуска декабря 2025](https://developer.android.com/blog/posts/whats-new-in-the-jetpack-compose-december-release).
Версии библиотек и плагинов — `gradle/libs.versions.toml`,
Gradle и SHA-256 дистрибутива — `gradle/wrapper/gradle-wrapper.properties`.

## Сборка на macOS / Linux

Нужны JDK 17, Android SDK Platform 36, Build Tools 35.0.0 и принятые
лицензии SDK. Первая сборка загружает Gradle и зависимости из интернета.
Задать `JAVA_HOME` на JDK 17 и `ANDROID_HOME` на SDK либо указать
`sdk.dir` в локальном `local.properties` (не сохранять в репозитории).

Из корня репозитория на проверенной машине:

```sh
cd tracker-app
export JAVA_HOME=/Users/alxshvarts/Library/Java/JavaVirtualMachines/temurin-17.0.13/Contents/Home
export ANDROID_HOME=/Users/alxshvarts/Library/Android/sdk
./gradlew :app:assembleDebug :app:lintDebug --console=plain
```

На другой машине заменить пути. Не использовать системный JDK 25 с этим
Gradle. Android Studio также должна использовать JDK 17 для Gradle.
Windows-скрипт `gradlew.bat` не включён по решению пользователя.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Lint: `app/build/reports/lint-results-debug.html`.

## Проверка запуска

Подключить Android 13 или запустить AVD API 33; при нескольких устройствах
добавить `-s SERIAL` к каждой команде adb:

```sh
"$ANDROID_HOME/platform-tools/adb" devices -l
"$ANDROID_HOME/platform-tools/adb" shell getprop ro.build.version.sdk
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" shell am start -W -n com.utbildning.tracker/.MainActivity
```

Ожидается API 33, успешный запуск экрана «Сегодня» без падения.
Название приложения берётся из `values/strings.xml` (английский по умолчанию)
или `values-ru/strings.xml`. Выбор языка внутри приложения реализован в S03.

## Проверки

С запущенным эмулятором из `tracker-app/`:

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest --console=plain
```

JVM-тесты находятся в `app/src/test/`, проверки Room, репозитория, UI и
напоминаний — в `app/src/androidTest/`. Для UI используется Android 16 / API 36.
Результат текущей проверки: [отчёт](../docs/checks/mvp/README.md).
Preview компилируются; рендер в Android Studio отдельно не проверялся.

Предыдущие контрольные точки: [S02](../docs/checks/s02/README.md),
[S03](../docs/checks/s03/README.md), [S04](../docs/checks/s04/README.md),
[S05](../docs/checks/s05/README.md), [S06](../docs/checks/s06/README.md).
Проверка S01 на Android 13 остаётся отложенной; API 36 её не заменяет.

## Демо-данные на эмуляторе

Явное предзаполнение реальной `tracker.db` тремя курсами (существующие данные сохраняются):

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
sh scripts/seed-emulator.sh
```

«Пейзажи», категория «Рисование»: 32 темы, ежедневно в 11:00.
«Задачи по C», категория «Программирование»: 71 тема.
«Шведский · A2», категория «Языки»: 5 демонстрационных тем.
С `seedToday=true` программирование получает ежедневное расписание на 15:00;
шведский — на 20:30. Для сегодняшнего дня скрипт создаёт примеры:
09:00 — «Пейзажи», 15:00 — «Задачи по C», 20:30 — «Шведский · A2».
Прежние демо-занятия «Пейзажей» в 15:00/20:30 меняют курс только при
отсутствии результата и истории тем. Уже отмеченные занятия сохраняются.
Списки пользователя лежат в `app/src/androidTest/assets/demo/`, включая повторы.
Повторный запуск пропускает уже созданные курсы, не сбрасывает прогресс.
В обычном запуске приложения демо-данные не создаются; без `seedDemo=true`
инструментальный тест пропускается. Физические устройства не поддерживаются. Скрипт запускает instrumentation через adb,
чтобы Gradle не удалил приложение вместе с демо-БД после завершения теста.

Дополнительный пример для просмотра редактора: «Лекции и практика по C» — 24 темы, две пройдены, расписание Вт 19:00 / Сб 11:00 с конечной датой. Создаётся один раз при наличии свободного цвета; повторное заполнение не меняет его прогресс.
