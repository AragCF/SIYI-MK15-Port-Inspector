# SIYI MK15 Port Inspector

Диагностическое Android-приложение для **SIYI MK15**. Первая практическая цель — определить, через какой доступный Android-приложению интерфейс приходит левый верхний трёхпозиционный переключатель **SA**, найти его фактический коммуникационный канал и показывать живое значение на экране.

Текущая версия: **1.0.0**.

## Рабочий процесс

Основной источник исходников теперь — этот репозиторий. Архивы между итерациями не нужны.

Локальный каталог на Windows:

```text
C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector
```

Первое получение проекта:

```bat
cd /d "C:\54\Projects\!0724 - Coating Robot"
git clone https://github.com/AragCF/SIYI-MK15-Port-Inspector.git
cd /d "C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector"
```

Дальнейшее обновление:

```bat
cd /d "C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector"
git pull
```

## Сборка APK

При установленной Android Studio:

```bat
cd /d "C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector"
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
BUILD_WINDOWS.bat
```

Готовый APK после локальной сборки:

```text
out\MK15PortInspector-1.0.0-debug.apk
```

Подробности исследования, безопасности, проверки SA и диагностики находятся в `00_README_RU.md`, `01_FINDINGS.md`, `02_TASK_SCOPE.md`, `03_TESTS.md` и `04_PROGRESS.md`.

> Первую проверку RC-каналов проводить на столе, не в полёте и без работающей силовой части.
