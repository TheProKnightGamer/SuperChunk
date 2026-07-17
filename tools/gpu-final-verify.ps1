<#
  Stage-3b final verification on THIS (fp64-less) device:
   A) GPU-vs-vanilla parity self-test (fp32-structural here; bit-exact target on 3070).
   B) requireFp64=true production guard: GPU must NOT engage on a non-fp64 device.
  Each phase boots, captures the relevant log lines, stops gracefully.
#>
$ErrorActionPreference='Continue'
$Root=(Join-Path $PSScriptRoot '..')
$Cfg="$Root\run\config\superchunk-gpu.properties"
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.17.10-hotspot'
function KillJava(){ Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -and ($_.CommandLine -match 'superchunk' -or $_.CommandLine -match 'forgeserverdev') } | ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force } catch {} } }
function Rcon([string[]]$c){ $a=@('--host','127.0.0.1','--port','25575','--password','bench')+$c; (& python "$Root\bench\rcon.py" @a 2>&1) -join "`n" }

function Boot([string]$log){
  KillJava; Start-Sleep -Milliseconds 800
  if(Test-Path "$Root\run\world"){ Remove-Item -Recurse -Force "$Root\run\world" }
  if(Test-Path $log){ Remove-Item -Force $log }
  $tee="& '$Root\gradlew.bat' -p '$Root' runServer --console=plain *>&1 | Tee-Object -FilePath '$log'"
  $srv=Start-Process powershell.exe -ArgumentList '-NoProfile','-NonInteractive','-Command',$tee -PassThru -WindowStyle Hidden
  $dl=(Get-Date).AddSeconds(600)
  while((Get-Date) -lt $dl){ if(Test-Path $log){ if(Select-String -Path $log -Pattern 'Done \(' -ErrorAction SilentlyContinue | Select-Object -First 1){ break } }; if($srv.HasExited){ break }; Start-Sleep -Seconds 3 }
  return $srv
}

# ---------- Phase A: GPU-vs-vanilla parity self-test ----------
Write-Host "=== PHASE A: GPU-vs-vanilla parity self-test (enabled=true, requireFp64=false, selftest.gpu_parity=true) ==="
@"
enabled=true
platformIndex=-1
deviceIndex=-1
requireFp64=false
selftest.noise=false
selftest.dfc=false
selftest.gpu_parity=true
"@ | Set-Content -Encoding ASCII $Cfg
$logA="$Root\gpu-verify-parity.log"
$srvA=Boot $logA
Start-Sleep -Seconds 5
Rcon @('stop') | Out-Null
$srvA.WaitForExit(60000) | Out-Null
KillJava
$rawA=((Get-Content $logA -Raw -ErrorAction SilentlyContinue) -replace "`0",'') -split "`r?`n"
Write-Host "--- parity result lines ---"
$rawA | Select-String 'GPU-vs-VANILLA parity test|GPU vs vanilla' | ForEach-Object { Write-Host ($_.Line -replace '.*\] ','') }

Start-Sleep -Seconds 2

# ---------- Phase B: requireFp64=true guard ----------
Write-Host ""
Write-Host "=== PHASE B: requireFp64=true guard (enabled=true, requireFp64=true) on this fp64-less device ==="
@"
enabled=true
platformIndex=-1
deviceIndex=-1
requireFp64=true
selftest.noise=false
selftest.dfc=false
selftest.gpu_parity=false
"@ | Set-Content -Encoding ASCII $Cfg
$logB="$Root\gpu-verify-guard.log"
$srvB=Boot $logB
Start-Sleep -Seconds 3
Rcon @('chunky shape square') | Out-Null
Rcon @('chunky radius 48') | Out-Null
Rcon @('chunky center 0 0') | Out-Null
Rcon @('chunky start') | Out-Null
Start-Sleep -Seconds 20
Rcon @('stop') | Out-Null
$srvB.WaitForExit(60000) | Out-Null
KillJava
$rawB=((Get-Content $logB -Raw -ErrorAction SilentlyContinue) -replace "`0",'') -split "`r?`n"
Write-Host "--- guard result ---"
$refuse = $rawB | Select-String 'REFUSING to engage the GPU' | Select-Object -First 1
Write-Host ("REFUSE LINE: " + ($(if($refuse){'yes -> ' + ($refuse.Line -replace '.*\] ','')}else{'NO (guard did not fire!)'})))
$ready = $rawB | Select-String 'OpenCL backend ready' | Select-Object -First 1
Write-Host ("BACKEND READY (should be NO): " + ($(if($ready){'YES - PROBLEM'}else{'no'})))
$routed = $rawB | Select-String 'DFC GPU routing ENABLED' | Select-Object -First 1
Write-Host ("ROUTING ENABLED (should be NO): " + ($(if($routed){'YES - PROBLEM'}else{'no'})))
$attempts = $rawB | Select-String 'gpuFillAttempts=[1-9]' | Select-Object -First 1
Write-Host ("ANY GPU FILL ATTEMPTS (should be NO): " + ($(if($attempts){'YES - PROBLEM'}else{'no'})))
$doneB = $rawB | Select-String 'Done \(' | Select-Object -First 1
Write-Host ("REACHED DONE: " + ($(if($doneB){'yes'}else{'NO'})))
$chunkB = $rawB | Select-String 'Task finished for|Task running' | Select-Object -Last 1
Write-Host ("CHUNKY: " + ($(if($chunkB){($chunkB.Line -replace '.*\]: ','')}else{'(none)'})))
