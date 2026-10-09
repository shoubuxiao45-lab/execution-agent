param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$GradleCache = (Join-Path $PSScriptRoot '.gradle-user-home'),
    [string]$Proxy = '',
    [string[]]$Tasks = @('build', 'test', 'assembleDebugAndroidTest')
)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin/jlink.exe'))) {
    throw '请用 -JdkHome 指定包含 javac 和 jlink 的完整 JDK 17 或 21。'
}
function Get-AsciiBuildPath([string]$Directory) {
    $taskPath = [IO.Path]::GetFullPath($Directory)
    if ($taskPath -notmatch '[^\x00-\x7F]') { return $taskPath }
    $taskHasher = [System.Security.Cryptography.SHA256]::Create()
    try { $taskHash = ([BitConverter]::ToString($taskHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskPath)))).Replace('-', '').Substring(0, 12) }
    finally { $taskHasher.Dispose() }
    $taskAlias = Join-Path ([IO.Path]::GetTempPath()) "ExecutionAgent-$taskHash"
    if (Test-Path $taskAlias) {
        $taskItem = Get-Item -LiteralPath $taskAlias
        if ($taskItem.LinkType -ne 'Junction' -or $taskItem.Target -ne $taskPath) {
            throw "临时路径已被其他内容占用：$taskAlias"
        }
    } else {
        New-Item -ItemType Junction -Path $taskAlias -Target $taskPath | Out-Null
    }
    return $taskAlias
}
# Aliases include JDK and cache: Java test-worker argument files also need ASCII paths.
New-Item -ItemType Directory -Force -Path $GradleCache | Out-Null
$taskRoot = Get-AsciiBuildPath $PSScriptRoot
$taskJdk = Get-AsciiBuildPath $JdkHome
$taskCache = Get-AsciiBuildPath $GradleCache
Write-Host "Gradle project: $taskRoot"
$savedJdk = $env:JAVA_HOME
$savedCache = $env:GRADLE_USER_HOME
$savedAndroidHome = $env:ANDROID_USER_HOME
try {
    $env:JAVA_HOME = $taskJdk
    $env:GRADLE_USER_HOME = $taskCache
    $env:ANDROID_USER_HOME = Join-Path $taskCache 'android-user'
    $taskArguments = @('-p', $taskRoot) + $Tasks + @('--no-daemon', '--console=plain', '--warning-mode=all')
    if ($Proxy) {
        $taskProxy = [Uri]$Proxy
        if ($taskProxy.Scheme -ne 'http' -or $taskProxy.UserInfo) { throw '仅支持不含凭据的 HTTP 代理地址。' }
        $taskArguments += @("-Dhttp.proxyHost=$($taskProxy.Host)", "-Dhttp.proxyPort=$($taskProxy.Port)",
            "-Dhttps.proxyHost=$($taskProxy.Host)", "-Dhttps.proxyPort=$($taskProxy.Port)")
    }
    & (Join-Path $taskRoot 'gradlew.bat') @taskArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle 失败，退出码 $LASTEXITCODE" }
} finally {
    $env:JAVA_HOME = $savedJdk
    $env:GRADLE_USER_HOME = $savedCache
    $env:ANDROID_USER_HOME = $savedAndroidHome
}
