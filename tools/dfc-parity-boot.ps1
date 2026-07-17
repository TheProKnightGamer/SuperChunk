$ErrorActionPreference='Continue'
$Root=(Join-Path $PSScriptRoot '..')
$Log="$Root\dfc-parity.log"
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.17.10-hotspot'
$env:JAVA_TOOL_OPTIONS='-Dsuperchunk.dfc.paritytest=true'
function KillJava(){ Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -and ($_.CommandLine -match 'superchunk' -or $_.CommandLine -match 'forgeserverdev') } | ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force } catch {} } }
KillJava; Start-Sleep -Milliseconds 800
if(Test-Path "$Root\run\world"){ Remove-Item -Recurse -Force "$Root\run\world" }
if(Test-Path $Log){ Remove-Item -Force $Log }
$tee="& '$Root\gradlew.bat' -p '$Root' runServer --console=plain *>&1 | Tee-Object -FilePath '$Log'"
$srv=Start-Process powershell.exe -ArgumentList '-NoProfile','-NonInteractive','-Command',$tee -PassThru -WindowStyle Hidden
$dl=(Get-Date).AddSeconds(600); $hit=$null
while((Get-Date) -lt $dl){
  if(Test-Path $Log){
    $h=Select-String -Path $Log -Pattern 'DFC parity self-test:' -ErrorAction SilentlyContinue | Select-Object -First 1
    if($h){ $hit=$h.Line; break }
    $d=Select-String -Path $Log -Pattern 'Done \(' -ErrorAction SilentlyContinue | Select-Object -First 1
    if($d){ Start-Sleep -Seconds 5; $h=Select-String -Path $Log -Pattern 'DFC parity self-test:' -ErrorAction SilentlyContinue | Select-Object -First 1; if($h){$hit=$h.Line}; break }
  }
  if($srv.HasExited){ break }
  Start-Sleep -Seconds 2
}
Write-Output ("PARITY_LINE: " + $hit)
KillJava
