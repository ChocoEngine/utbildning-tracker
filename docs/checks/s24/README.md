# S24 — адаптивная иконка, 2026-09-26

Подключён утверждённый левый вариант: единая асимметричная клякса с зелёной,
терракотовой и сиреневой диагональными полосами на тёплом фоне.
Средний/правый варианты и рамка листа концептов не используются.

Чистый векторный исходник: [ink-launcher.svg](../../../design/icons/ink-launcher.svg).
Он вручную воспроизводит выбранный концепт; это векторная подготовка ресурса,
не обрезка исходного листа и не новая генерация дизайна.
Цвета: #78AC80 / #DC9475 / #9D95CC, фон #FCFAF6.

Ресурсы в `tracker-app/app/src/main/res/`:

- `drawable/ic_launcher_foreground.xml`: прозрачный вектор, 108×108 dp,
  силуэт примерно 54×54 dp в центре; фон и рамка в передний план не встроены.
- `drawable/ic_launcher_monochrome.xml`: тот же цельный силуэт без полос.
- `values/launcher_colors.xml`: сплошной фон для всей маски.
- `mipmap-anydpi-v26/ic_launcher.xml` и `ic_launcher_round.xml`:
  background / foreground / monochrome.
- Manifest подключает `android:icon` и `android:roundIcon`.

Размеры слоёв и безопасные поля сверены с
[официальными требованиями Android](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

## Сборка и фактическая проверка

Среда: JDK 17, Gradle 8.13, `Medium_Phone_API_36`, Android 16 / API 36,
Pixel Launcher (`com.google.android.apps.nexuslauncher`).

Из `tracker-app/`, после изменения ресурсов и manifest:

```sh
./gradlew :app:assembleDebug :app:lintDebug --console=plain
```

**BUILD SUCCESSFUL in 11s**. Lint: **0 ошибок / 23 предупреждения**;
MissingApplicationIcon устранено; появилось ObsoleteSdkInt для
`mipmap-anydpi-v26` (minSdk 33). Путь оставлен по явному требованию S24.
Остальные предупреждения существующего кода/зависимостей описаны в [общем отчёте](../mvp/README.md).
APK установлен через `adb install -r`; нажатие новой иконки открыло «Сегодня».
Kotlin-код, экраны, схема БД и бизнес-логика в S24 не менялись.
Общий прогон 13 JVM + 100 инструментальных тестов относится к состоянию
S22/S23 перед добавлением ресурсов иконки; после S24 повторены сборка/lint
и проверки launcher, не полный набор тестов.

- [x] Список приложений: [all-apps.png](screenshots/all-apps.png).
- [x] Закреплённая иконка на рабочем столе: [home-color.png](screenshots/home-color.png).
- [x] Круговая маска: оба снимка выше; силуэт не обрезан, полосы различимы.
- [x] Тематические иконки: включены через Wallpaper & style → Themed icons;
  [home-themed.png](screenshots/home-themed.png). Видна цельная монохромная клякса.
- [ ] Скруглённая квадратная маска на установленном launcher: в настройках
  Pixel Launcher этой AVD нет переключателя формы; `cmd overlay list` не
  содержит переключаемых icon-shape overlay. Этот режим не проверен.
- [ ] Android 13 и физическое устройство: не проверялись; Android 13 отложен.

Все три снимка просмотрены. После проверки тематические иконки выключены;
цветной значок оставлен на рабочем столе. S24 остаётся частичным из-за
непроверенной скруглённой квадратной маски; наличие адаптивного XML не
выдаётся за фактическую проверку этого режима.
