param([string]$LogPath = (Join-Path $PSScriptRoot 'cs-verify.log'))
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.17.10-hotspot'
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = (Join-Path $PSScriptRoot 'gradlew.bat')
$psi.Arguments = ("-p `"$PSScriptRoot`" runServer --console=plain")
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.WorkingDirectory = $PSScriptRoot
$p = [System.Diagnostics.Process]::Start($psi)
$sw = [System.IO.StreamWriter]$LogPath
$sentStop = $false
$savedSeen = $false
while (-not $p.HasExited) {
  $line = $p.StandardOutput.ReadLine()
  if ($null -ne $line) {
    $sw.WriteLine($line); $sw.Flush()
    if ((-not $sentStop) -and ($line -match 'Done \(')) {
      Start-Sleep -Seconds 2
      $p.StandardInput.WriteLine('save-all flush'); $p.StandardInput.Flush()
      Start-Sleep -Seconds 3
      $p.StandardInput.WriteLine('stop'); $p.StandardInput.Flush()
      $sentStop = $true
    }
    if ($line -match 'All dimensions are saved') { $savedSeen = $true }
  }
  if ($p.WaitForExit(50)) { break }
}
$sw.Close()
Write-Output ("sentStop=" + $sentStop + " savedSeen=" + $savedSeen + " exit=" + $p.ExitCode)
