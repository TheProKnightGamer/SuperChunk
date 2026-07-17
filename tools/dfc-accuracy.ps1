param(
    [int]$Radius = 160,
    [int]$MaxGenSeconds = 240
)
$ErrorActionPreference = 'Continue'
$Root = (Join-Path $PSScriptRoot '..')
$RconPy = Join-Path $Root 'bench\rcon.py'
$WorldDir = Join-Path $Root 'run\world'
$RegionDir = Join-Path $WorldDir 'region'
$Toml = Join-Path $Root 'run\config\c2me.toml'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.17.10-hotspot'

function Log($m){ Write-Host ("[acc] {0}" -f $m) }
function Rcon([string[]]$cmds){
    $a = @('--host','127.0.0.1','--port','25575','--password','bench') + $cmds
    return (& python $RconPy @a 2>&1) -join "`n"
}
function KillJava(){
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
      Where-Object { $_.CommandLine -and ($_.CommandLine -match 'superchunk' -or $_.CommandLine -match 'forgeserverdev') } |
      ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force } catch {} }
}
function SetDfc($val){
    # Read/modify/write defensively: only rewrite if we actually have content,
    # so a transient locked/empty read can't truncate the toml to 0 bytes.
    $c = Get-Content $Toml -Raw
    if ([string]::IsNullOrEmpty($c)) { Log "WARN: toml read empty, skipping SetDfc"; return }
    $c = $c -replace 'useDensityFunctionCompiler = .*', "useDensityFunctionCompiler = $val"
    [System.IO.File]::WriteAllText($Toml, $c)
    Log "set useDensityFunctionCompiler = $val"
}

function GenAndDump([string]$dfc, [string]$dumpOut, [string]$logPath){
    Log "=== RUN dfc=$dfc ==="
    KillJava; Start-Sleep -Milliseconds 800
    if (Test-Path $WorldDir){ Remove-Item -Recurse -Force $WorldDir }
    if (Test-Path $logPath){ Remove-Item -Force $logPath }
    SetDfc $dfc

    $teeCmd = "& '$Root\gradlew.bat' -p '$Root' runServer --console=plain *>&1 | Tee-Object -FilePath '$logPath'"
    $server = Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile','-NonInteractive','-Command',$teeCmd -PassThru -WindowStyle Hidden

    # wait for Done
    $dl = (Get-Date).AddSeconds(600); $up = $false
    while((Get-Date) -lt $dl){
        if(Test-Path $logPath){
            $h = Select-String -Path $logPath -Pattern 'Done \(' -ErrorAction SilentlyContinue | Select-Object -First 1
            if($h){ $up=$true; break }
        }
        if($server.HasExited){ break }
        Start-Sleep -Seconds 2
    }
    if(-not $up){ Log "server did not boot for dfc=$dfc"; KillJava; return $false }
    Log "booted"
    Start-Sleep -Seconds 3

    Rcon @('chunky shape square') | Out-Null
    Rcon @("chunky radius $Radius") | Out-Null
    Rcon @('chunky start') | Out-Null

    $dl2 = (Get-Date).AddSeconds($MaxGenSeconds); $fin=$false
    while((Get-Date) -lt $dl2){
        Start-Sleep -Seconds 3
        $c = Get-Content $logPath -ErrorAction SilentlyContinue
        if($c | Select-String -Pattern 'Task finished for'){ $fin=$true; break }
        if($c | Select-String -Pattern 'Wrapping node'){ Log "WRAPPING NODE EXCEPTION during dfc=$dfc"; break }
        if($server.HasExited){ break }
    }
    Log "gen finished=$fin"

    # graceful save + stop so .mca are flushed
    Rcon @('save-all flush') | Out-Null
    Start-Sleep -Seconds 4
    Rcon @('stop') | Out-Null
    $server.WaitForExit(60000) | Out-Null
    Start-Sleep -Seconds 2
    KillJava
    Start-Sleep -Seconds 1

    Log "dumping signatures -> $dumpOut"
    & python (Join-Path $Root 'tools\heightmap_dump.py') $RegionDir $dumpOut
    # snapshot region dir for post-mortem diff
    $snap = $dumpOut -replace '\.txt$', '-region'
    if (Test-Path $snap){ Remove-Item -Recurse -Force $snap }
    Copy-Item -Recurse $RegionDir $snap
    Log "snapshot region -> $snap"
    return $true
}

$dumpOff = Join-Path $Root 'tools\sig-dfc-off.txt'
$dumpOn  = Join-Path $Root 'tools\sig-dfc-on.txt'

$dumpOff2 = Join-Path $Root 'tools\sig-dfc-off2.txt'
GenAndDump 'false' $dumpOff (Join-Path $Root 'acc-off.log') | Out-Null
GenAndDump 'false' $dumpOff2 (Join-Path $Root 'acc-off2.log') | Out-Null
GenAndDump 'true'  $dumpOn  (Join-Path $Root 'acc-on.log')  | Out-Null

# Restore shipped DFC-on default
SetDfc 'true'

# Control: off vs off (determinism of the comparison itself)
$o1=@{}; $o1s=@{}; Get-Content $dumpOff  | ForEach-Object { $p=$_ -split ' '; $k="$($p[0]) $($p[1])"; $o1[$k]=$p[2]; $o1s[$k]=$p[3] }
$o2=@{}; $o2s=@{}; Get-Content $dumpOff2 | ForEach-Object { $p=$_ -split ' '; $k="$($p[0]) $($p[1])"; $o2[$k]=$p[2]; $o2s[$k]=$p[3] }
$cc=0;$cm=0;$cx=0
foreach($k in $o1.Keys){ if($o2.ContainsKey($k) -and $o1s[$k] -eq 'minecraft:full' -and $o2s[$k] -eq 'minecraft:full'){ $cc++; if($o1[$k] -eq $o2[$k]){$cm++}else{$cx++} } }
Write-Output "CONTROL_OFFvsOFF COMMON_FULL=$cc MATCH=$cm MISMATCH=$cx"

# Compare (only chunks that reached minecraft:full in BOTH runs)
Log "=== COMPARISON (full chunks only) ==="
$off = @{}; $offStat=@{}; Get-Content $dumpOff | ForEach-Object { $p=$_ -split ' '; $k="$($p[0]) $($p[1])"; $off[$k]=$p[2]; $offStat[$k]=$p[3] }
$on  = @{}; $onStat=@{};  Get-Content $dumpOn  | ForEach-Object { $p=$_ -split ' '; $k="$($p[0]) $($p[1])"; $on[$k]=$p[2];  $onStat[$k]=$p[3] }
$common=0; $match=0; $mismatch=0; $examples=@()
foreach($k in $off.Keys){
    if($on.ContainsKey($k) -and $offStat[$k] -eq 'minecraft:full' -and $onStat[$k] -eq 'minecraft:full'){
        $common++
        if($off[$k] -eq $on[$k]){ $match++ } else { $mismatch++; if($examples.Count -lt 10){ $examples += $k } }
    }
}
Write-Output "OFF_CHUNKS=$($off.Count) ON_CHUNKS=$($on.Count) COMMON_FULL=$common MATCH=$match MISMATCH=$mismatch"
if($mismatch -gt 0){ Write-Output ("MISMATCH_EXAMPLES: " + ($examples -join '; ')) }
else { Write-Output 'RESULT: terrain bit-identical across all common full chunks (DFC on == DFC off)' }
