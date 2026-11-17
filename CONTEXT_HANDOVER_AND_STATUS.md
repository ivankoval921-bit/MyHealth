# CONTEXT_HANDOVER_AND_STATUS — Моё здоровье (MyHealth)

- **Разработчик:** `Volodymyr's Ilchenko`
- **Текущая версия:** `1.0.1` (`versionCode = 2`), проект `D:\Antigravity_Projects\MyHealth`
- **GitHub:** `https://github.com/ivankoval921-bit/MyHealth` (ветки `main`, `dev`, авто-CI в `.github/workflows/build.yml`, скрипт релиза `publish.ps1`)
- **Цель:** Единый долговременный Android-ассистент и дневник здоровья (`MyHealth` / «Моё здоровье») без дробления организма на изолированные болезни, с историческим контекстом хронических vs острых симптомов, экстренной безопасностью и автообновлением из GitHub Releases.

## Реализовано в v1.0.1 (все замечания Брата + автообновление + авторство)
1. **Хронические ощущения vs острые «Красные флаги» + нетипичная стенокардия** (`RedFlagSafetyEngine.kt`, `SymptomAndClarifierEngine.kt`, `GeminiHealthClient.kt`):
   - Передаёт прошлые события (`priorEvents`) и свежие замеры (`recentVitals`) в офлайн-движок и Gemini (`store = false`).
   - Отличает повторяющиеся хронические жалобы (например, «холодный мизинец / плохая чувствительность пальцев» ≥2 раза на дистанции >24 ч без резкого ухудшения) от внезапных острых неврологических нарушений.
   - Выявляет нетипичные кардиосимптомы (*«давит ниже горла»*, *«душит/сдавливает под нижней челюстью»*, *«тяжеловато дышать»*, *«тянет левую руку»*).
2. **Проактивные диагностические шаги и фото тонометра/часов** (`SymptomAndClarifierEngine.kt`, `MedicalDocumentEngine.kt`, `GeminiHealthClient.kt`, `MainViewModel.kt`):
   - При головокружении, боли, слабости или кардиожалобах без свежего АД (<2 ч) показывает карточку «Рекомендуемый шаг прямо сейчас» (*«Померяйте сейчас давление и пульс»* с кнопками `+ Ввести АД/пульс` и `📷 Фото тонометра`).
   - Распознаёт цифры с экрана прибора через ML Kit OCR (включая вертикальные LCD-строки) и опционально Gemini Vision, сохраняя результат с уровнем достоверности `VALIDATED_DEVICE` (`[Мед. прибор]`).
3. **Таймер самоконтроля, голосовая карточка 112 (TTS DE/RU) и авто-SMS** (`HealthModels.kt`, `HealthDatabase.kt` DB v2, `BackupManager.kt`, `MainActivity.kt`, `MyHealthApp.kt`):
   - В профиль добавлены `homeAddress` и опция `autoSmsCheckInEnabled`.
   - Кнопки `⏱ 1 м / 3 м / 5 м` запускают контрольный таймер; по истечении включается сирена и вибрация на 60 с с кнопкой «Я В ПОРЯДКЕ — ОТМЕНИТЬ». Если пользователь не отметился, открывается карточка диспетчера `112` (`DE / RU` + `🔊 Озвучить` через `TextToSpeech` + `📞 Позвонить 112`) и при явном согласии + праве `SEND_SMS` уходит авто-SMS доверенному контакту (с честным предупреждением об ограничениях GSM/Android и правил 112 в Германии).
4. **Модуль расширения датчиков и авторство/обновления** (`SensorBridge.kt`, `AppMeta.kt`, `UpdateChecker.kt`, `DoctorReportPdfGenerator.kt`):
   - Интерфейс `SensorAdapter` под будущий Этап 2 (`Health Connect` / BLE-часы) с честной пометкой об измерениях по требованию на часах.
   - Указаны разработчик `Volodymyr's Ilchenko` и версия (`v1.0.1`) в шапке приложения, разделе «О программе», `appatlas.json`, документации и PDF-выписке врачу.
   - `UpdateChecker` + `UpdateCheckReceiver`: проверяет `version.json` на GitHub Releases при запуске, по расписанию (Android push-уведомление) и по кнопке; скачивает `MyHealth.apk` и запускает установку через `FileProvider`.

## Проверенные сборки и тесты (v1.0.1)
- `.\build.ps1 -Task testDebugUnitTest`: **BUILD SUCCESSFUL** — **14 / 14** тестов в `HealthEnginesTest` (`0 failures`, `0 errors`, `0 skipped`).
- `.\build.ps1 -Task assembleDebug`: **BUILD SUCCESSFUL** — `app\build\outputs\apk\debug\app-debug.apk` (SHA256 `CA870B50FB01E9415773C518807C0E26154D5A8F378A31079BDDAF44AEE3BBA1`).
- **Устройство `QOLBY5MJ7565ZDAY` (Realme `RMX5061`, Android 16):** установлен финальный APK (`versionCode=2`, `versionName=1.0.1`), проверены шапка с `Volodymyr's Ilchenko`, модальная карточка `112` (RU/DE), вкладка `Профиль` и кнопки на `Сегодня`. Тестовая база на телефоне сброшена в чистое состояние.

## Следующие шаги (Этап 2)
1. Подключение `Android Health Connect` и BLE-мониторов к `SensorBridge`.
2. Экран `CameraX` с автофокусом при резкости для фото глаз/кожи/полосок мочи (`pH мочи`) и экранов приборов.
3. При выпуске новой версии повышать `versionCode`/`versionName` в `app/build.gradle.kts` (и `appatlas.json`) и публиковать через `.\publish.ps1 -Notes "..."`.
