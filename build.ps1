param(
    [ValidateSet('assembleDebug', 'testDebugUnitTest', 'lintDebug')]
    [string]$Task = 'assembleDebug'
)
$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$gradlePath = 'D:\AndroidDev\gradle-8.11.1\bin\gradle.bat'
$javaDir = 'D:\AndroidDev\jdk17'
$sdkDir = 'D:\AndroidDev\sdk'
foreach ($requiredPath in @($gradlePath, (Join-Path $javaDir 'bin\java.exe'), (Join-Path $sdkDir 'platforms\android-35\android.jar'))) {
    if (!(Test-Path -LiteralPath $requiredPath)) { throw "Missing required component: $requiredPath" }
}
$env:JAVA_HOME = $javaDir
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_USER_HOME = 'D:\AndroidDev\android-home'
$env:GRADLE_USER_HOME = 'D:\AndroidDev\gradle-home'
$env:TEMP = 'D:\AndroidDev\tmp'
$env:TMP = $env:TEMP
$env:GRADLE_OPTS = '-Djava.io.tmpdir=D:/AndroidDev/tmp'
$outputDir = Join-Path $projectDir 'outputs'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$logPath = Join-Path $outputDir ($Task + '.log')
[IO.File]::WriteAllText($logPath, '', [Text.UTF8Encoding]::new($true))
Push-Location $projectDir
try {
    $ErrorActionPreference = 'Continue'
    & $gradlePath --no-daemon --console=plain $Task 2>&1 | ForEach-Object {
        $line = $_.ToString()
        [IO.File]::AppendAllText($logPath, $line + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
        Write-Output $line
    }
    $resultCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($resultCode -ne 0) { throw "Gradle exited with code $resultCode. Log: $logPath" }
    if ($Task -eq 'assembleDebug') {
        $apkPath = Join-Path $projectDir 'app\build\outputs\apk\debug\app-debug.apk'
        if (!(Test-Path -LiteralPath $apkPath)) { throw 'Gradle did not produce expected APK.' }
        Write-Output "APK: $apkPath"
        Get-FileHash -LiteralPath $apkPath -Algorithm SHA256
    }
} finally {
    Pop-Location
}
