param(
    [string]$JavaHome = "C:\Program Files\Java\jdk-25.0.2"
)

$ErrorActionPreference = "Stop"

$mavenCandidates = @(
    "C:\Program Files\Apache\Maven\bin",
    "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin"
)

if (-not (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
    Write-Error "Java 25 not found at '$JavaHome'."
}

$mavenBin = $mavenCandidates | Where-Object { Test-Path (Join-Path $_ "mvn.cmd") } | Select-Object -First 1
if (-not $mavenBin) {
    Write-Error "Maven not found in known locations. Install Apache Maven or IntelliJ bundled Maven first."
}

$javaBin = Join-Path $JavaHome "bin"
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ([string]::IsNullOrWhiteSpace($userPath)) {
    $userPath = ""
}

# Fix legacy malformed entry where java and maven were concatenated without a semicolon.
$userPath = $userPath -replace [regex]::Escape("$javaBin $mavenBin"), "$javaBin;$mavenBin"

$existing = @()
if ($userPath.Length -gt 0) {
    $existing = $userPath.Split(";") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
}

$ordered = New-Object System.Collections.Generic.List[string]
$addUnique = {
    param([string]$value)
    if (-not [string]::IsNullOrWhiteSpace($value) -and -not $ordered.Contains($value)) {
        $ordered.Add($value)
    }
}

& $addUnique $javaBin
& $addUnique $mavenBin
foreach ($entry in $existing) {
    & $addUnique $entry
}

$newUserPath = ($ordered -join ";")

[Environment]::SetEnvironmentVariable("JAVA_HOME", $JavaHome, "User")
[Environment]::SetEnvironmentVariable("Path", $newUserPath, "User")

Write-Host "[OK] JAVA_HOME set to $JavaHome"
Write-Host "[OK] Maven bin added to user PATH: $mavenBin"
Write-Host "[OK] Open a new terminal and run: mvn -v"

