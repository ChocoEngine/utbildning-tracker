# Android-приложение

Открывать `tracker-app/` в Android Studio. Один модуль `app`, namespace и
applicationId — `com.utbildning.tracker`; minSdk 33, compileSdk/targetSdk 36.
Текущая Room-схема — v8, миграции 1→8. Действующее поведение описано в
[ТЗ](../docs/PRODUCT_SPEC.md), открытые проверки — в
[плане](../docs/OPEN_WORK.md).

## Окружение

JDK 17, Android SDK Platform 36 и Build Tools 35.0.0. Версии Gradle, AGP,
Kotlin, Compose и библиотек закреплены в `gradle/libs.versions.toml` и
`gradle/wrapper/gradle-wrapper.properties`. Указать `JAVA_HOME` и
`ANDROID_HOME` либо локальный `sdk.dir` в `local.properties`.

Из этого каталога:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Lint:
`app/build/reports/lint-results-debug.html`.

## Запуск и instrumentation

Установить APK поверх существующей версии через `adb install -r`; удаление
приложения стирает его локальную БД. Для итоговой проверки миграции использовать
тестовое устройство с известным набором сохранённых данных.

```sh
./gradlew :app:connectedDebugAndroidTest --console=plain
```

Полный instrumentation выполнять на отдельном эмуляторе: тесты меняют БД,
настройки, язык и разрешения. Демо-наполнение тестового эмулятора:

```sh
sh scripts/seed-emulator.sh
```

Сценарии, требующие полуночи, reboot или системных разрешений, запускаются
отдельно; условия описаны в [правилах тестирования](../docs/TESTING.md).
Краткие результаты старых прогонов — в
[сводке](../docs/VERIFICATION_BASELINE.md); подробности доступны в Git-истории.
