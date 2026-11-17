# CONTEXT_HANDOVER_AND_STATUS — Моё здоровье (MyHealth)

- **Разработчик:** `Volodymyr's Ilchenko`
- **Текущая версия:** `1.0.3` (`versionCode = 4`), проект `D:\Antigravity_Projects\MyHealth`
- **GitHub:** `https://github.com/ivankoval921-bit/MyHealth` (ветки `main`, `dev`, авто-CI в `.github/workflows/build.yml`, скрипт релиза `publish.ps1`)
- **Приватность репозитория:** в коде и репозитории отсутствуют личные номера, адреса и API-ключи; персональные контакты родственников и тестовый номер заполняются только в локальной SQLite БД на устройстве (`myhealth_vault.db`).

## Реализовано в v1.0.3
1. **Тестовый аварийный вызов (модель 112) на любой номер** (`MainViewModel.kt`, `MainActivity.kt`, `MyHealthApp.kt`):
   - Пользователь вписывает произвольный тестовый номер в Карточке 112 или Профиле (либо 1 тапом выбирает любого из родственников).
   - Кнопка `📞 Тест + голос` звонит на тестовый номер (`CALL_PHONE` при наличии права, иначе открывает номеронабиратель), включает громкую связь (`AudioManager`) и зачитывает DE/RU карточку с префиксом учебного тестового вызова (никогда не вызывает реальный 112).
   - Кнопка `⏱ Тест 10с` прогоняет учебный 10-секундный цикл контроля безопасности с сиреной и автопереходом на тестовый звонок.
2. **Неограниченный список родственников + передача GPS** (`HealthModels.kt`, `HealthDatabase.kt` DB v4, `BackupManager.kt`, `MainActivity.kt`, `MyHealthApp.kt`):
   - В профиль добавлены `testEmergencyPhone` и список `relativesJson` (`EmergencyRelative`: жена, муж, брат, дети, племянницы и т.д.).
   - В Карточке 112 и Профиле доступны быстрый звонок `📞`, `SMS+GPS`, `💬 SMS+GPS всем` (массовая отправка ссылки Google Maps и точных координат) и `🌍 Поделиться GPS` (WhatsApp/Telegram/SMS), а `LocationManager.getCurrentLocation` (API 30+) обновляет живые координаты.

## Проверенные сборки и тесты (v1.0.3)
- `.\build.ps1 -Task testDebugUnitTest`: **BUILD SUCCESSFUL** — **16 / 16** тестов в `HealthEnginesTest` (`0 failures`, `0 errors`, `0 skipped`).
- `.\build.ps1 -Task assembleDebug`: **BUILD SUCCESSFUL** — `app\build\outputs\apk\debug\app-debug.apk` (`SHA256 95F1E20580CA7D61D969C48FAD594D94D483388045133C8A6BAD4C9485CE3E65`).
- **Устройство `QOLBY5MJ7565ZDAY` (Realme `RMX5061`, Android 16):** установлено `v1.0.3` (`versionCode=4`), БД обновлена до `user_version=4`, в профилях на телефоне добавлен тестовый номер брата и родственники, диалог `112 · Тест-звонок · Родственники` проверен визуально.

## Следующие шаги (Этап 2)
1. Интеграция `Android Health Connect` и BLE-датчиков в `SensorBridge`.
2. Экран `CameraX` с автоспуском по резкости.
3. Публикация следующих релизов через `.\publish.ps1 -Notes "..."`.
