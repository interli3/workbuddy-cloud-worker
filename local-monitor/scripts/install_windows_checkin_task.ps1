$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'checkin.ps1'
$powershell = (Get-Command powershell.exe).Source

$action = New-ScheduledTaskAction -Execute $powershell -Argument (
    '-NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $scriptPath)
$triggers = @(
    New-ScheduledTaskTrigger -Daily -At '09:00'
    New-ScheduledTaskTrigger -Daily -At '21:00'
    New-ScheduledTaskTrigger -AtLogOn
)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -WakeToRun `
    -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 15)

Register-ScheduledTask -TaskName 'WorkBuddyMonitorCheckin' -Action $action `
    -Trigger $triggers -Settings $settings -Description 'WorkBuddy Monitor daily check-in' `
    -Force | Out-Null

Write-Host 'Installed task: WorkBuddyMonitorCheckin'
