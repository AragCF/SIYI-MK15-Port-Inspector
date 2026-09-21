# MK15 Port Inspector 1.0.0

Диагностическое Android-приложение для **SIYI MK15**. Первая задача — без root определить фактический канал физического тумблера **SA** (левый верхний трёхпозиционный переключатель возле антенны) и показать его живое значение на экране.

## Что установлено по документации SIYI

Старое руководство MK15 v1.1 указывает заводское назначение **SA → CH5**, однако mapping каналов переназначаемый. Поэтому приложение не считает CH5 неизменным.

Используется опубликованный SIYI Datalink SDK:

- `0x48 Request All Channel Mappings` — найти фактический канал физического `SA`;
- физический `SA` определяется как `type=5, entity_id=0`;
- `0x42 Request Channel Data` — получить живые значения `CH1..CH16`, обычно около `1050..1950`;
- основной путь для обычного APK без root — встроенный **USB COM / CP2102**;
- альтернативный SDK-интерфейс `/dev/ttyHS0` приложение только диагностирует, потому что Android/SELinux могут закрывать доступ.

## Что делает приложение

1. Показывает 16 коммуникационных каналов и их живые значения.
2. Читает mapping `0x48` и выделяет канал SA.
3. Включает `0x42` на 4 Гц и умеет выключать поток.
4. Команды включения и остановки `0x42` отправляются три раза подряд.
5. **Не изменяет mapping** и не использует `0x4A`.
6. Журналирует Android `KeyEvent` и `MotionEvent`.
7. Сканирует USB, `InputDevice`, кандидаты `/dev/tty*`, `/dev/input*`, `/sys/class/tty`, доступные `/proc` и сетевые интерфейсы.
8. Сохраняет диагностический текстовый отчёт.

## Безопасность

SIYI предупреждает, что выдача RC-каналов через `0x42` использует тот же интерфейс, что и телеметрия.

**Первую проверку проводить только на столе, не в полёте и без работающей силовой части.** Закройте QGroundControl и другие программы, которые могут занимать USB COM.

## Локальный каталог Windows

```text
C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector
```

Первое получение:

```bat
cd /d "C:\54\Projects\!0724 - Coating Robot"
git clone https://github.com/AragCF/SIYI-MK15-Port-Inspector.git
cd /d "C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector"
```

Обновление:

```bat
cd /d "C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector"
git pull
```

## Сборка APK под Windows

Нужны Android Studio/JDK 17 и Android SDK Platform 34 + Build-Tools 34.0.0.

```bat
cd /d "C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector"
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
BUILD_WINDOWS.bat
```

Сценарий проверяет Java/SDK, запускает независимый тест протокола, при необходимости загружает официальный Gradle 8.7 и собирает debug APK.

Результат:

```text
out\MK15PortInspector-1.0.0-debug.apk
```

Если API 34 не установлен:

```bat
set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
"%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
"%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-34" "build-tools;34.0.0" "platform-tools"
BUILD_WINDOWS.bat
```

## Первая проверка SA

1. На MK15: `SIYI TX → Datalink → Connection → USB COM`.
2. Для первого прогона использовать 57600 бод, если в SIYI TX не настроено другое значение.
3. Запустить приложение.
4. Нажать **«Сканировать порты»**.
5. Нажать **«Подключить USB COM»** и разрешить Android доступ к USB.
6. Нажать **«Mapping 0x48»**. Ожидается обнаружение `type=5/entity_id=0 → CH...`.
7. Нажать **«RC 4 Гц 0x42»**.
8. Переключить SA по трём положениям. Для заводской настройки ожидаются примерно `1050 / 1500 / 1950`.
9. Нажать **«СТОП RC»**.
10. При проблеме нажать **«Сохранить отчёт»** и передать полученный файл.

## Если USB COM не найден

Диагностический отчёт должен показать:

- USB VID/PID и интерфейсы;
- наличие CP2102/другого BULK IN/OUT;
- наличие и права `/dev/ttyHS0`;
- доступные `/dev/tty*`;
- `/proc/tty/drivers`;
- Android `InputDevice`.

Это позволяет выбирать следующий путь по фактам, а не по предположениям.
