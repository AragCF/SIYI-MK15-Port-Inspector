# MK15 C/D SDK 1.0.0

Дата выпуска: 2026-09-24.

## Назначение

Подключаемая Android-библиотека фиксирует аппаратно подтверждённый результат исследования SIYI MK15 и предназначена для последующего отдельного приложения управления дроном и роботом.

## Что вошло

- модуль `mk15-sdk/`;
- готовый Android AAR `MK15-CD-SDK-1.0.0.aar`;
- нативный ARM64 UART через `/dev/ttyHS0`, 115200 8N1;
- runtime mapping через SIYI `0x48`;
- живой поток каналов через `0x42`, 4 Гц;
- события C/D `RELEASED / PRESSED / INTERMEDIATE`;
- машинно-читаемое аппаратное свидетельство `mk15-sdk/evidence/MK15_CD_EVIDENCE.json`;
- автономный тест `host-tests/Mk15SdkSelfTest.java`.

## Аппаратно подтверждено

```text
C = CH10
D = CH11
released = 1050
pressed  = 1950
```

Два независимых аппаратных сеанса дали 391 живой кадр каналов и 28 маркированных действий C/D без несоответствий.

SHA-256 исходных отчётов:

```text
F33D11B3880FC6358E9D09F7B3672B0B3BD4F3C80D3FA8BDB073F140D2DDB6D2  MK15_Report_20260924_134701_v1_4_0.zip
4189C17C22E496F6B55229B86AFED804A948B01A3E0CF26C19BAB9333C6D54E9  MK15_Report_20260924_210500_v1_4_0.zip
```

## Проверка сборки

GitHub Actions run `36052471874` — success.

Проверены:
- Windows PowerShell 5.1;
- обе ARM64 JNI-библиотеки;
- автономные тесты протокола и SDK;
- сборка Port Inspector APK;
- сборка `mk15-sdk` Release AAR;
- публикация общего артефакта `MK15-Port-Inspector-and-CD-SDK`.

Artifact ID: `10831325938`.
Artifact digest: `sha256:4c11d615d0a6b437db4d18775f81c741c0524ad6d68e58f0bcddbfccb3d2dbd7`.

## Подключение

Из этого репозитория:

```gradle
dependencies {
    implementation project(':mk15-sdk')
}
```

Готовый AAR из сборки:

```text
out/MK15-CD-SDK-1.0.0.aar
```

Полная инструкция: `mk15-sdk/README.md`.
Итоговый аппаратный отчёт: `docs/MK15_CD_FINAL_SPEC.md`.
