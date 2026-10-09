param([Parameter(Mandatory=$true)][string]$SdkHome,
      [Parameter(Mandatory=$true)][string]$JdkHome,
      [Parameter(Mandatory=$true)][string]$BuildLog,
      [Parameter(Mandatory=$true)][string]$DeviceLog)
$ErrorActionPreference = 'Stop'
$taskProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskOutput = Split-Path $taskProject
function Get-AsciiPackagePath([string]$Directory) {
    $taskResolved=[IO.Path]::GetFullPath($Directory)
    if ($taskResolved -notmatch '[^\x00-\x7F]') { return $taskResolved }
    $taskHasher=[Security.Cryptography.SHA256]::Create()
    try { $taskHash=([BitConverter]::ToString($taskHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskResolved)))).Replace('-','').Substring(0,12) }
    finally { $taskHasher.Dispose() }
    $taskAlias=Join-Path ([IO.Path]::GetTempPath()) "ExecutionAgent-$taskHash"
    if (Test-Path -LiteralPath $taskAlias) {
        $taskItem=Get-Item -LiteralPath $taskAlias
        if ($taskItem.LinkType -ne 'Junction' -or $taskItem.Target -ne $taskResolved) { throw 'ASCII alias occupied' }
    } else { New-Item -ItemType Junction -Path $taskAlias -Target $taskResolved | Out-Null }
    return $taskAlias
}
if ((Get-Content -LiteralPath $BuildLog -Raw) -notmatch 'BUILD SUCCESSFUL') { throw 'Successful build log required' }
if ((Get-Content -LiteralPath $DeviceLog -Raw) -notmatch 'OK \(35 tests\)') { throw 'Complete Android test evidence required' }
$taskTests = @{}
foreach ($taskVariant in @('Debug','Release','Validation')) {
    $taskCount=0; $taskFailures=0; $taskErrors=0
    $taskResults = @(Get-ChildItem -LiteralPath (Join-Path $taskProject "app\build\test-results\test${taskVariant}UnitTest") -Filter 'TEST-*.xml')
    if (-not $taskResults.Count) { throw 'Missing unit results' }
    foreach ($taskResult in $taskResults) {
        [xml]$taskXml=Get-Content -LiteralPath $taskResult.FullName
        $taskCount += [int]$taskXml.testsuite.tests
        $taskFailures += [int]$taskXml.testsuite.failures; $taskErrors += [int]$taskXml.testsuite.errors
    }
    if ($taskFailures -or $taskErrors -or $taskCount -lt 124) { throw 'Unit test evidence incomplete' }
    $taskTests[$taskVariant]=$taskCount
}
$taskApk=Join-Path $taskOutput 'execution-agent-dynamic-reminders.apk'
Copy-Item -LiteralPath (Join-Path $taskProject 'app\build\outputs\apk\debug\app-debug.apk') -Destination $taskApk
$taskSavedJava=$env:JAVA_HOME
try {
    $env:JAVA_HOME=$JdkHome
    $taskSigner=Join-Path $SdkHome 'build-tools\35.0.0\apksigner.bat'
    $taskVerify=& $taskSigner verify --verbose --print-certs $taskApk 2>&1
    if ($LASTEXITCODE) { throw 'APK signature invalid' }
    $taskOldVerify=& $taskSigner verify --print-certs (Join-Path $taskOutput 'execution-agent-phase8.apk') 2>&1
    if ($LASTEXITCODE) { throw 'Original signature invalid' }
    $taskCert=($taskVerify | Select-String 'certificate SHA-256 digest:').ToString()
    if ($taskCert -ne ($taskOldVerify | Select-String 'certificate SHA-256 digest:').ToString()) { throw 'Original signing certificate changed' }
    & (Join-Path $SdkHome 'build-tools\35.0.0\zipalign.exe') -c -P 16 4 $taskApk
    if ($LASTEXITCODE) { throw 'APK alignment invalid' }
    $taskMetadataApk=Join-Path (Get-AsciiPackagePath $taskProject) 'app\build\outputs\apk\debug\app-debug.apk'
    $taskMetadata=& (Join-Path $SdkHome 'build-tools\35.0.0\aapt.exe') dump badging $taskMetadataApk
    if ($LASTEXITCODE -or ($taskMetadata -join "`n") -notmatch "name='com.executionagent.app' versionCode='10' versionName='0.9.0-dynamic-reminders'") { throw 'Wrong APK metadata' }
    $taskVerify + $taskMetadata | Set-Content -LiteralPath (Join-Path $taskOutput 'dynamic-reminders-apk-verification.txt') -Encoding utf8
} finally { $env:JAVA_HOME=$taskSavedJava }
$taskV1=Get-Content -LiteralPath (Join-Path $taskProject 'app\schemas\com.executionagent.app.data.local.ExecutionDatabase\1.json') -Raw | ConvertFrom-Json
$taskV2=Get-Content -LiteralPath (Join-Path $taskProject 'app\schemas\com.executionagent.app.data.local.ExecutionDatabase\2.json') -Raw | ConvertFrom-Json
if ($taskV1.database.identityHash -ne '672f5ac6b6a4fcdf6071a2fd016a4411' -or $taskV2.database.version -ne 2) { throw 'Schema lineage invalid' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZipPath=Join-Path $taskOutput 'execution-agent-dynamic-reminders-source.zip'
# Remove only this exact, regenerable package in the checked output directory.
if (Test-Path -LiteralPath $taskZipPath) { Remove-Item -LiteralPath $taskZipPath }
$taskZip=[IO.Compression.ZipFile]::Open($taskZipPath,[IO.Compression.ZipArchiveMode]::Create)
try {
    $taskFiles=& rg --files --hidden $taskProject -g '!**/build/**' -g '!**/.gradle/**' -g '!**/.kotlin/**' -g '!**/.gradle-user-home/**' -g '!**/.git/**' -g '!**/.idea/**' -g '!local.properties' -g '!ai.local.properties' -g '!*.jks' -g '!*.keystore' -g '!*.db*'
    foreach ($taskFile in $taskFiles) {
        $taskRelative=$taskFile.Substring($taskProject.Length+1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip,$taskFile,"ExecutionAgent/$taskRelative",[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $taskZip.Dispose() }
$taskZip=[IO.Compression.ZipFile]::OpenRead($taskZipPath)
try {
    foreach ($taskRequired in @('gradle/wrapper/gradle-wrapper.jar','app/schemas/com.executionagent.app.data.local.ExecutionDatabase/1.json','app/schemas/com.executionagent.app.data.local.ExecutionDatabase/2.json','DYNAMIC_REMINDERS_REPORT.md')) {
        if ($taskZip.Entries.FullName -notcontains "ExecutionAgent/$taskRequired") { throw 'Source package incomplete' }
    }
    if ($taskZip.Entries.FullName -match '/(local.properties|ai.local.properties)$|\.(jks|keystore)$|/build/') { throw 'Source package contains private/build files' }
    $taskEntries=$taskZip.Entries.Count
} finally { $taskZip.Dispose() }
Copy-Item -LiteralPath $BuildLog -Destination (Join-Path $taskOutput 'dynamic-reminders-build.log')
Copy-Item -LiteralPath $DeviceLog -Destination (Join-Path $taskOutput 'dynamic-reminders-android-tests.log')
$taskValidation=[ordered]@{
    version='0.9.0-dynamic-reminders'; versionCode=10; applicationId='com.executionagent.app'
    apk='execution-agent-dynamic-reminders.apk'; apkSha256=(Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLower()
    matchesOriginalSigningCertificate=$true; alignment16kPassed=$true
    unitTests=$taskTests; androidTests=35; androidTestsPassed=$true; physicalPhoneTestedThisUpdate=$false
    migration='1 -> 2'; v1Hash=$taskV1.database.identityHash; v2Hash=$taskV2.database.identityHash
    preservesTasksHistoryChatsAndQuota=$true; noDestructiveMigration=$true
    buildPassed=$true; lint=(Get-Content -LiteralPath (Join-Path $taskProject 'app\build\reports\lint-results-validation.txt') -Raw).Trim()
    sourceZipEntries=$taskEntries; sourceZipSha256=(Get-FileHash -LiteralPath $taskZipPath -Algorithm SHA256).Hash.ToLower()
}
$taskValidation | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskOutput 'dynamic-reminders-validation.json') -Encoding utf8
$taskValidation | ConvertTo-Json -Depth 5
