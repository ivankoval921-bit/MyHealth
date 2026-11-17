# Публикация новой версии Моё здоровье (MyHealth) на GitHub с автообновлением на телефон.
# ВАЖНО: сборка выполняется локально с D:\AndroidDev\android-home\debug.keystore,
# чтобы телефон мог установить обновление поверх уже стоящего APK без конфликта подписи.
param(
    [Parameter(Mandatory=$true)][string]$Notes,
    [switch]$SkipGitPush
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repo = 'ivankoval921-bit/MyHealth'
$gradleText = Get-Content "$root\app\build.gradle.kts" -Raw -Encoding UTF8
$code = [int]([regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
$name = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $code -or -not $name) {
    throw "Не удалось прочитать versionCode/versionName из app/build.gradle.kts"
}

Write-Host "1/4 Проверка unit-тестов и сборка APK v$name ($code)..."
& "$root\build.ps1" -Task testDebugUnitTest
if ($LASTEXITCODE -ne 0) { throw "Тесты не пройдены" }
& "$root\build.ps1" -Task assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Сборка APK не удалась" }

$apkPath = "$root\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) { throw "APK не найден: $apkPath" }

$distDir = "$root\dist"
New-Item -ItemType Directory -Force $distDir | Out-Null
$destApk = "$distDir\MyHealth.apk"
Copy-Item $apkPath $destApk -Force

$versionObj = [ordered]@{
    code = $code
    name = $name
    notes = $Notes
    apkUrl = "https://github.com/$repo/releases/latest/download/MyHealth.apk"
} | ConvertTo-Json -Compress
[IO.File]::WriteAllText("$distDir\version.json", $versionObj, (New-Object Text.UTF8Encoding $false))
[IO.File]::WriteAllText("$distDir\release_notes.md", $Notes, (New-Object Text.UTF8Encoding $false))

if (-not $SkipGitPush) {
    Write-Host "2/4 Git push main + dev..."
    Set-Location $root
    & git push -u origin main
    if ($LASTEXITCODE -ne 0) { throw "git push main не удался" }
    & git checkout -B dev
    & git push -u origin dev
    if ($LASTEXITCODE -ne 0) { throw "git push dev не удался" }
    & git checkout main
}

Write-Host "3/4 Создание / обновление релиза v$name на GitHub..."
$tag = "v$name"
$existing = & gh release view $tag --repo $repo 2>$null
if ($LASTEXITCODE -eq 0) {
    & gh release upload $tag $destApk "$distDir\version.json" --repo $repo --clobber
    & gh release edit $tag --repo $repo --title "MyHealth v$name" --notes-file "$distDir\release_notes.md" --latest
} else {
    & gh release create $tag $destApk "$distDir\version.json" --repo $repo --target main --title "MyHealth v$name" --notes-file "$distDir\release_notes.md" --latest
}
if ($LASTEXITCODE -ne 0) { throw "Ошибка публикации релиза $tag на GitHub" }

$mb = [math]::Round((Get-Item $destApk).Length / 1MB, 1)
Write-Host "4/4 ГОТОВО: опубликована v$name (сборка $code), $mb MB -> https://github.com/$repo/releases/tag/$tag"
