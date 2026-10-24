param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail([string]$message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

function Read-Utf8([string]$path) {
    [System.IO.File]::ReadAllText((Join-Path $root $path), [System.Text.UTF8Encoding]::new($false))
}

function Write-Utf8([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText((Join-Path $root $path), $text, [System.Text.UTF8Encoding]::new($false))
}

if ($Version -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$') {
    Fail "'$Version' is not a valid version. Examples: 1.2.0, 2.0.0-beta.1"
}
$tag = "v$Version"

if (git status --porcelain) { Fail 'The working tree has uncommitted changes.' }
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne 'main') { Fail "Releases are cut from main only (current branch: $branch)." }
if (git tag --list $tag) { Fail "Tag $tag already exists." }
$pom = Read-Utf8 'pom.xml'
$current = [regex]::Match($pom, '<revision>([^<]+)</revision>').Groups[1].Value
if (-not $current) { Fail 'No <revision> found in pom.xml.' }
if ($current -ne $Version) {
    Write-Utf8 'pom.xml' ($pom -replace "<revision>$([regex]::Escape($current))</revision>", "<revision>$Version</revision>")
}
$changelog = Read-Utf8 'CHANGELOG.md'
$unreleased = [regex]::Match($changelog, '(?s)## \[Unreleased\]\s*(.*?)(?=\r?\n## \[|\z)')
if (-not $unreleased.Success -or -not $unreleased.Groups[1].Value.Trim()) {
    Fail 'CHANGELOG.md has no notes under [Unreleased]. Describe the changes of this version first.'
}
$date = Get-Date -Format 'yyyy-MM-dd'
$changelog = $changelog.Remove($unreleased.Index, $unreleased.Length).Insert($unreleased.Index,
    "## [Unreleased]`n`n## [$Version] - $date`n`n" + $unreleased.Groups[1].Value.Trim() + "`n")
Write-Utf8 'CHANGELOG.md' $changelog
$wrapper = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.\mvnw.cmd' } else { './mvnw' }
& $wrapper -B -ntp clean verify
if ($LASTEXITCODE -ne 0) {
    git checkout -- pom.xml CHANGELOG.md
    Fail 'Build or tests failed; pom.xml and CHANGELOG.md were restored.'
}
git add pom.xml CHANGELOG.md
git commit -m "Release $Version"
git tag -a $tag -m "efxBans $Version"

Write-Host ''
Write-Host "$tag is ready. JAR: target/efxBans-$Version.jar" -ForegroundColor Green
Write-Host 'To publish:  git push origin main --follow-tags'
