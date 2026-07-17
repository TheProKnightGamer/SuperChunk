<#
.SYNOPSIS
  Stage-3b live GPU density-fill integration test (fp32 iGPU, integration-only).
  Boots the server with the GPU backend ENABLED, pregens a region via Chunky over
  RCON, and reports whether GPU density fills actually executed on worker threads.

.PARAMETER Radius
  Chunky pre-gen radius in chunks (default 32 -> ~4225 chunks).

.PARAMETER MaxGenSeconds
  Hard cap on the gen window (default 240).
#>
[CmdletBinding()]
param(
  [int]$Radius        = 32,
  [int]$MaxGenSeconds = 240,
  [string]$LogName    = 'gpu-integration.log',
  [string]$RconHost   = '127.0.0.1',
  [int]$RconPort      = 25575,
  [string]$RconPass   = 'bench',
  [string]$JavaHome   = 'C:\Program Files\Java\jdk-17.0.17.10-hotspot'
)
$ErrorActionPreference = 'Continue'
$Root    = (Join-Path $PSScriptRoot '..')
$Log     = Join-Path $Root $LogName
$RconPy  = Join-Path $Root 'bench\rcon.py'
$World   = Join-Path $Root 'run\world'

function Log($m){ Write-Host ("[gpu-int] {0}" -f $m) }
function Rcon([string[]]$c){ $a=@('--host',$RconHost,'--port',"$RconPort",'--password',$RconPass)+$c; (& python $RconPy @a 2>&1) -join "`n" }
function KillJava(){ Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -and ($_.CommandLine -match 'superchunk' -or $_.CommandLine -match 'forgeserverdev') } | ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force } catch {} } }

KillJava; Start-Sleep -Milliseconds 800
if (Test-Path $World) { Remove-Item -Recurse -Force $World }
if (Test-Path $Log)   { Remove-Item -Force $Log }

Log "starting runServer (JAVA_HOME=$JavaHome) ..."
$env:JAVA_HOME = $JavaHome
$gradlew = Join-Path $Root 'gradlew.bat'
$tee = "& '$gradlew' -p '$Root' runServer --console=plain *>&1 | Tee-Object -FilePath '$Log'"
$srv = Start-Process powershell.exe -ArgumentList '-NoProfile','-NonInteractive','-Command',$tee -PassThru -WindowStyle Hidden
Log "server launcher PID = $($srv.Id)"

function WaitFor([string]$pat,[int]$to){ $dl=(Get-Date).AddSeconds($to); while((Get-Date)-lt $dl){ if(Test-Path $Log){ $h=Select-String -Path $Log -Pattern $pat -ErrorAction SilentlyContinue | Select-Object -First 1; if($h){ return $h.Line } }; if($srv.HasExited){ throw "server exited before '$pat'" }; Start-Sleep -Seconds 2 }; throw "timeout waiting for '$pat'" }

Log "waiting for boot (Done) ..."
$done = WaitFor 'Done \(' 600
Log "server up: $done"
Start-Sleep -Seconds 8   # let RCON + chunky fully initialize

Log "starting chunky (square, radius $Radius) ..."
$rShape = Rcon @('chunky shape square'); Log "shape: $rShape"
$rRad   = Rcon @("chunky radius $Radius"); Log "radius: $rRad"
$rCtr   = Rcon @('chunky center 0 0'); Log "center: $rCtr"
$start = Get-Date
$rStart = Rcon @('chunky start'); Log "start: $rStart"

$cap = (Get-Date).AddSeconds($MaxGenSeconds)
$reDone='Task finished for .*Processed:\s*([\d,]+)\s*chunks'
$reUpd ='Task running for .*Processed:\s*([\d,]+)\s*chunks.*Rate:\s*([\d.]+)\s*cps'
while($true){
  Start-Sleep -Seconds 5
  $c = Get-Content $Log -ErrorAction SilentlyContinue
  $d = $c | Select-String -Pattern $reDone | Select-Object -Last 1
  if($d){ Log "chunky finished: $($d.Line)"; break }
  if($srv.HasExited){ Log 'WARNING: server exited during gen'; break }
  if((Get-Date) -ge $cap){
    $u = $c | Select-String -Pattern $reUpd | Select-Object -Last 1
    if($u){ Log "cap reached: $($u.Line)" }
    Rcon @('chunky cancel') | Out-Null; Rcon @('chunky cancel') | Out-Null
    break
  }
}

Start-Sleep -Seconds 2
Log "stopping server gracefully ..."
Rcon @('stop') | Out-Null
$srv.WaitForExit(90000) | Out-Null
Start-Sleep -Seconds 2
KillJava

# ---- Analysis (logs may be UTF-16; strip NULs) ----
$raw = (Get-Content $Log -Raw -ErrorAction SilentlyContinue) -replace "`0",''
$lines = $raw -split "`r?`n"
Write-Host ""
Write-Host "==================== GPU INTEGRATION SUMMARY ===================="
$dev = $lines | Select-String 'Selected device:' | Select-Object -Last 1
if($dev){ Write-Host ("DEVICE: " + ($dev.Line -replace '.*Selected device:\s*','')) }
$routed = $lines | Select-String 'DFC GPU routing ENABLED' | Select-Object -First 1
Write-Host ("ROUTING ENABLED LINE: " + ($(if($routed){'yes'}else{'NO'})))
$compiled = $lines | Select-String 'DF compiled to GPU' | Select-Object -Last 1
if($compiled){ Write-Host ("LAST DF-COMPILE: " + ($compiled.Line -replace '.*\] ','')) }
$statLines = $lines | Select-String 'density-fill stats'
if($statLines){ Write-Host ("LAST STATS: " + (($statLines | Select-Object -Last 1).Line -replace '.*\] ','')) }
else { Write-Host 'LAST STATS: (none logged)' }
$errs = $lines | Select-String -Pattern 'OpenCL error|CL_OUT_OF|CLException|deadlock|fill failed|noise upload failed|kernel build failed'
Write-Host ("CL ERROR/FALLBACK LINES: " + ($errs | Measure-Object).Count)
if($errs){ ($errs | Select-Object -First 8) | ForEach-Object { Write-Host ("  " + ($_.Line -replace '.*\] ','')) } }
$exc = $lines | Select-String -Pattern 'Exception|Caused by|at com.ishland|at dev.superchunk' | Where-Object { $_.Line -notmatch 'fill failed|disabling GPU' }
Write-Host ("EXCEPTION LINES: " + ($exc | Measure-Object).Count)
if($exc){ ($exc | Select-Object -First 6) | ForEach-Object { Write-Host ("  " + $_.Line) } }
$reachedDone = ($lines | Select-String 'Done \(' | Measure-Object).Count
Write-Host ("REACHED DONE: " + $(if($reachedDone -gt 0){'yes'}else{'NO'}))
$stopped = $lines | Select-String 'Releasing .* GPU resource owner' | Select-Object -Last 1
Write-Host ("SHUTDOWN RELEASE LINE: " + ($(if($stopped){($stopped.Line -replace '.*\] ','')}else{'(none)'})))
Write-Host "================================================================"
