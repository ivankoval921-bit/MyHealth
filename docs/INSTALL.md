# Установка, сборка и публикация (MyHealth v1.0.2)

Разработчик: **Volodymyr's Ilchenko**

## Сборка на ПК
Требуются компоненты в `D:\AndroidDev` (JDK 17, Gradle 8.11.1, Android SDK 35).

```powershell
Set-Location D:\Antigravity_Projects\MyHealth
.\build.ps1 -Task testDebugUnitTest
.\build.ps1 -Task assembleDebug
```

Готовый файл: `app\build\outputs\apk\debug\app-debug.apk`.

## Установка на телефон через ADB
```powershell
adb install -r D:\Antigravity_Projects\MyHealth\app\build\outputs\apk\debug\app-debug.apk
```

## Публикация новой версии с автообновлением на телефон (GitHub Release)
Чтобы телефон получил уведомление и установил обновление поверх существующего APK с единой подписью (`D:\AndroidDev\android-home\debug.keystore`):
1. Повысьте `versionCode` и `versionName` в `app/build.gradle.kts` (и `version`/`docs_version` в `appatlas.json`).
2. Запустите:
```powershell
powershell -ExecutionPolicy Bypass -File D:\Antigravity_Projects\MyHealth\publish.ps1 -Notes "Описание изменений"
```
Скрипт прогонит тесты, соберёт APK, запушит `main`, `dev` и тег `v<version>` на GitHub, создаст GitHub Release `latest` с `MyHealth.apk` и `version.json`, после чего установленное приложение само обнаружит новую версию при открытии/в фоне и покажет уведомление с кнопкой «Обновить».
