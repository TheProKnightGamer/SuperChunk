param(
    [string]$LogPath = (Join-Path $PSScriptRoot '..dfc-repro.log'),
    [int]$Radius = 256,
    [int]$MaxGenSeconds = 240
)
$ErrorActionPreference = 'Continue'
$Root = (Join-Path $PSScriptRoot '..')
$RconPy = Join-Path $Root 'bench\rcon.py'
$WorldDir = Join-Path $Root 'run\world'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.17.10-hotspot'

function Log($m){ Write-Host ("[repro] {0}" -f $m) }
function Rcon([string[]]$cmds){
    $a = @('--host','127.0.0.1','--port','25575','--password','bench') + $cmds
    return (& python $RconPy @a 2>&1) -join "`n"
}
function KillJava(){
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
      Where-Object { $_.CommandLine -and ($_.CommandLine -match 'superchunk' -or $_.CommandLine -match 'forgeserverdev') } |
      ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force } catch {} }
}

Log 'killing leftover java'; KillJava; Start-Sleep -Milliseconds 800
if (Test-Path $WorldDir){ Log 'wiping world'; Remove-Item -Recurse -Force $WorldDir }
if (Test-Path $LogPath){ Remove-Item -Force $LogPath }

Log 'starting server'
$teeCmd = "& '$Root\gradlew.bat' -p '$Root' runServer --console=plain *>&1 | Tee-Object -FilePath '$LogPath'"
$server = Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile','-NonInteractive','-Command',$teeCmd -PassThru -WindowStyle Hidden

function WaitFor($pat,$to){
    $dl=(Get-Date).AddSeconds($to)
    while((Get-Date) -lt $dl){
        if(Test-Path $LogPath){
            $h = Select-String -Path $LogPath -Pattern $pat -ErrorAction SilentlyContinue | Select-Object -First 1
            if($h){ return $h.Line }
        }
        if($server.HasExited){ return "<<SERVER EXITED>>" }
        Start-Sleep -Seconds 2
    }
    return $null
}

Log 'waiting for Done'
$done = WaitFor 'Done \(' 600
Log "boot: $done"
if(-not $done -or $done -eq '<<SERVER EXITED>>'){ Log 'server failed to boot'; KillJava; exit 2 }

Start-Sleep -Seconds 3
Log 'starting chunky gen (forces structure_starts)'
Rcon @('chunky shape square') | Out-Null
Rcon @("chunky radius $Radius") | Out-Null
Rcon @('chunky start') | Out-Null

$dl = (Get-Date).AddSeconds($MaxGenSeconds)
$found = $false
while((Get-Date) -lt $dl){
    Start-Sleep -Seconds 3
    $c = Get-Content $LogPath -ErrorAction SilentlyContinue
    $exc = $c | Select-String -Pattern 'Unsupported transformation on Wrapping node|UnsupportedOperationException|Exception generating new chunk|java.lang.IllegalStateException' | Select-Object -First 1
    if($exc){ Log "EXCEPTION FOUND: $($exc.Line)"; $found=$true; break }
    $fin = $c | Select-String -Pattern 'Task finished for' | Select-Object -Last 1
    if($fin){ Log "chunky finished: $($fin.Line)"; break }
    if($server.HasExited){ Log 'server exited'; break }
}

Log 'stopping server'
Rcon @('stop') | Out-Null
$server.WaitForExit(60000) | Out-Null
Start-Sleep -Seconds 2
KillJava
Write-Output ("REPRO_EXCEPTION_FOUND=" + $found)
