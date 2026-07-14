param(
    [string]$Package = "com.e7orbit.debug",
    [string]$Destination = ".\captures"
)

$ErrorActionPreference = "Stop"
$adb = "E:\Lib\AndroidSdk\platform-tools\adb.exe"

if (-not (Test-Path $adb)) {
    throw "ADB not found: $adb"
}

$device = & $adb get-state 2>$null
if ($device -ne "device") {
    throw "No ADB device. Start MuMu 12 first."
}

function Export-RemoteFile {
    param(
        [string]$RemotePath,
        [string]$LocalPath
    )

    $parent = Split-Path $LocalPath
    New-Item -ItemType Directory -Path $parent -Force | Out-Null

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $adb
    $startInfo.Arguments = "exec-out run-as $Package cat $RemotePath"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $output = [System.IO.File]::Create($LocalPath)
    try {
        $process.StandardOutput.BaseStream.CopyTo($output)
    }
    finally {
        $output.Dispose()
    }
    $errorText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Export failed for ${RemotePath}: $errorText"
    }
}

$root = Join-Path $Destination "e7orbit-export"
New-Item -ItemType Directory -Path $root -Force | Out-Null

$entries = & $adb shell run-as $Package ls files/diagnostics
if ($LASTEXITCODE -ne 0) {
    throw "Cannot read diagnostics. Confirm the Debug APK is installed."
}

foreach ($entry in $entries) {
    $name = $entry.Trim()
    if (-not $name) {
        continue
    }
    if ($name -eq "logs") {
        $logEntries = & $adb shell run-as $Package ls files/diagnostics/logs
        foreach ($logEntry in $logEntries) {
            $logName = $logEntry.Trim()
            if ($logName) {
                Export-RemoteFile `
                    "files/diagnostics/logs/$logName" `
                    (Join-Path $root "logs\$logName")
            }
        }
    }
    else {
        Export-RemoteFile `
            "files/diagnostics/$name" `
            (Join-Path $root $name)
    }
}

Write-Host "Diagnostics exported to: $((Resolve-Path $root).Path)"
