param(
    [string]$ProjectRoot,
    [string]$RepoRoot,
    [string]$DownloadBase = 'https://raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Modules/main/modules'
)

$ErrorActionPreference = 'Stop'

if (-not $ProjectRoot -or $ProjectRoot.Trim().Length -eq 0) {
    # scripts/ -> project root
    $ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
}
if (-not $RepoRoot -or $RepoRoot.Trim().Length -eq 0) {
    $RepoRoot = Join-Path $ProjectRoot 'repo'
}

$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$RepoRoot = [System.IO.Path]::GetFullPath($RepoRoot)

$modulesRoot = Join-Path $ProjectRoot 'modules'
$repoModulesDir = Join-Path $RepoRoot 'modules'
$modulesJsonPath = Join-Path $RepoRoot 'modules.json'
$checksumsJsonPath = Join-Path $RepoRoot 'checksums.json'

Write-Host "[INFO] Project root: $ProjectRoot"
Write-Host "[INFO] Repo root: $RepoRoot"

if (-not (Test-Path -LiteralPath $modulesRoot)) {
    throw "Modules directory not found: $modulesRoot"
}

if (-not (Test-Path -LiteralPath $RepoRoot)) {
    throw "Repo directory not found: $RepoRoot"
}

New-Item -ItemType Directory -Path $repoModulesDir -Force | Out-Null

# Remove old module jars so deleted/renamed modules don't linger in the repo output.
Get-ChildItem -LiteralPath $repoModulesDir -Filter '*.jar' -File -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue

$entries = New-Object System.Collections.Generic.List[object]
$seenIds = @{}

Get-ChildItem -LiteralPath $modulesRoot -Directory | ForEach-Object {
    $moduleDir = $_.FullName
    $moduleName = $_.Name

    $descriptorPath = Join-Path $moduleDir 'target\classes\module.json'
    if (-not (Test-Path -LiteralPath $descriptorPath)) {
        return
    }

    $targetDir = Join-Path $moduleDir 'target'
    if (-not (Test-Path -LiteralPath $targetDir)) {
        return
    }

    $jar = Get-ChildItem -LiteralPath $targetDir -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike 'original-*' -and $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $jar) {
        Write-Warning "Skipping '$moduleName' because no built module jar was found in target/."
        return
    }

    $descriptor = Get-Content -LiteralPath $descriptorPath -Raw | ConvertFrom-Json
    if (-not $descriptor.id) {
        throw "Missing 'id' in descriptor: $descriptorPath"
    }

    $id = [string]$descriptor.id
    if ($seenIds.ContainsKey($id)) {
        throw "Duplicate module id '$id' from '$moduleName' and '$($seenIds[$id])'."
    }
    $seenIds[$id] = $moduleName

    $destJarName = "$id.jar"
    $destJarPath = Join-Path $repoModulesDir $destJarName
    Copy-Item -LiteralPath $jar.FullName -Destination $destJarPath -Force

    $author = ''
    if ($descriptor.author) {
        $author = [string]$descriptor.author
    } elseif ($descriptor.authors -and $descriptor.authors.Count -gt 0) {
        $author = [string]$descriptor.authors[0]
    }

    $entry = [ordered]@{
        id = $id
        name = if ($descriptor.name) { [string]$descriptor.name } else { $id }
        version = if ($descriptor.version) { [string]$descriptor.version } else { '1.0.0' }
        description = if ($descriptor.description) { [string]$descriptor.description } else { '' }
        author = $author
        download_url = "$DownloadBase/$destJarName"
        image_url = ''
    }

    $hash = (Get-FileHash -LiteralPath $destJarPath -Algorithm SHA256).Hash.ToLowerInvariant()

    $entries.Add([pscustomobject]@{
        Id = $id
        Entry = $entry
        JarName = $destJarName
        Hash = $hash
    })
}

if ($entries.Count -eq 0) {
    throw 'No built modules with target/classes/module.json and target/*.jar were found.'
}

$sorted = $entries | Sort-Object Id

$modulesJson = ($sorted | ForEach-Object { $_.Entry }) | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($modulesJsonPath, $modulesJson + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))

$checksums = [ordered]@{}
$sorted | ForEach-Object {
    $checksums[$_.JarName] = $_.Hash
}
$checksumsJson = $checksums | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText($checksumsJsonPath, $checksumsJson + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))

Write-Host "[OK] Synced $($sorted.Count) module artifact(s) to '$repoModulesDir'."
Write-Host "[OK] Updated '$modulesJsonPath' and '$checksumsJsonPath'."

