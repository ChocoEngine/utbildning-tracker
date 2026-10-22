# Проверки приложения

Текущий порядок и ещё не выполненные критерии итоговой версии — в
[плане](OPEN_WORK.md). Этот файл содержит команды и ограничения методов.

## Обычный прогон

JDK 17, Android SDK 36. Из `tracker-app/`:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
./gradlew :app:connectedDebugAndroidTest --console=plain
```

Первый запуск собирает APK и выполняет JVM/lint; `assembleDebugAndroidTest`
только компилирует инструментальные тесты. Второй запуск выполняет их на
подключённом устройстве. Использовать отдельный тестовый AVD: тесты меняют
БД, язык, разрешения и уведомления. Число тестовых методов и набор пропусков
фиксировать по свежему отчёту, не по старому описанию. Assumption-пропуск не
считать успешной проверкой. Визуальные снимки требуют просмотра человеком.

Для утилиты оркестрации из корня репозитория:

```sh
tracker-app/gradlew -p tools/task-orchestrator test --console=plain
```

## Отдельные системные сценарии

`OvernightAlarmDeliveryTest` и `OpenMidnightTest` требуют запуска около
полуночи; обычный прогон может пропустить их по assumptions. Реальный reboot
и отзыв/возврат разрешений у `ReminderRecoveryTest` требуют внешних действий
между фазами `recovery_phase` (`prepare`, `denied`, `clicked_skipped`,
`cleanup`). Полный instrumentation сам по себе не доказывает доставку ночью,
работу после reboot или другую версию Android.

`EmulatorDemoSeedTest`, `EmulatorLongTitleSeedTest` и
`EmulatorAcceptanceSeedTest` наполняют эмулятор по явным аргументам
`seedDemo=true`, `keepThreeDemo=true`, `seedLongTitle=true`,
`seedAcceptance=true`; без них пропускаются. `scripts/seed-emulator.sh` использует
демо-набор. Не запускать заполнение и разрушительные сценарии на единственной
копии личных данных.

Для доказательства обновления поверх предыдущей версии установить APK через
`adb install -r` без очистки БД и сверить данные до/после. Для уведомления
наблюдать фактическую доставку и действие; факт регистрации alarm недостаточен.
Отчёт должен указывать ревизию, APK, API, локаль, конфигурацию экрана,
команды, success/failure/skip и ограничения.
