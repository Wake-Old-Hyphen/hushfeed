<#
.SYNOPSIS
    Runs the patch audit: every check in audit/catalog.json against a patched APK and a phone.

.DESCRIPTION
    The catalog holds one entry per patch with the checks that prove it does what its
    description says. This script executes them and writes the results for the TikTok version
    under test to audit/results/, so the next retarget can run the same checks and compare.

    Steps are data. "apk" steps read the patched APK itself (emptied files, the manifest, the
    resource table, strings in the dex) and need no phone. Every other step drives the phone
    through adb and the verification probe (tools/verification-probe), which runs inside
    TikTok and answers in logcat: "set" writes a Hushfeed setting, "probe" asks a question and
    holds its answer to a regex, "log" waits for a logcat line, "shot" saves a screenshot for a
    person to judge against the check's review text. A check whose decisions are all
    expectations is marked auto; one with a "shot" ends as "review" until a verdict is merged
    with -ApplyVerdicts.

    Settings a patch's checks change are read first and written back after its last check,
    and TikTok is restarted then if any of its checks restarted it, so each patch starts from
    the phone's own settings. Taps come from a fresh uiautomator dump or from the probe's
    click by view id or description, never from fixed coordinates, and the runner refuses to
    tap anything labeled like an action that reaches other people (Follow, Message, Send,
    Post, Repost, Duet, Stitch, Go LIVE, Buy, Request).

    Repeating it on a new TikTok version: patch the full catalog with scripts/patch-for-device.ps1
    (signed with the phone's key), build and install the probe with
    tools/verification-probe/build.ps1 -Install, run this with -Apk and -Install, review the
    screenshots, merge the verdicts, then diff the new results file against the previous one.
    concepts/patch-development.md has the whole routine.

.EXAMPLE
    audit/run-audit.ps1 -Apk C:\scratch\hushfeed-0.74.0-signed.apk -Serial $env:HUSHFEED_DEVICE_SERIAL -Install

.EXAMPLE
    audit/run-audit.ps1 -Apk C:\scratch\hushfeed-0.74.0-signed.apk -NoDevice

.EXAMPLE
    audit/run-audit.ps1 -Apk C:\scratch\hushfeed-0.74.0-signed.apk -Serial R5CT139QJ5F -Only 'Hide the LIVE button,category:Comments'

.EXAMPLE
    audit/run-audit.ps1 -ApplyVerdicts C:\scratch\verdicts.json -Results audit\results\tiktok-47.1.4.json
#>
[CmdletBinding()]
param(
    [string]$Apk,
    [string]$Serial = $env:HUSHFEED_DEVICE_SERIAL,
    [switch]$Install,
    [string]$Catalog,
    [string]$Only,
    [string]$OutDir,
    [string]$Results,
    [string]$Adb,
    [string]$Aapt2,
    [string]$LeaseDir = $env:HUSHFEED_DEVICE_LEASE_DIR,
    [switch]$NoDevice,
    [string]$ApplyVerdicts,
    [switch]$Validate
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$package = 'com.zhiliaoapp.musically'
if (-not $Catalog) { $Catalog = Join-Path $PSScriptRoot 'catalog.json' }

# Words on a control that reaches other people. A tap whose target starts with one of these is
# refused. "Following" is on the list because on someone else's profile that button unfollows.
$outwardWords = '^(follow|follows|following|followers|follow back|unfollow|add(?! comment)|add friend|message|send|post|repost|share to|duet|stitch|go live|buy|subscribe|request|join|like|liked|reply|gift|vote|invite|block|report)\b'

function Write-Summary {
    param($Run)
    $counts = [ordered]@{ pass = 0; fail = 0; review = 0; inconclusive = 0; error = 0; skipped = 0 }
    foreach ($patch in $Run.patches) {
        foreach ($check in $patch.checks) {
            $status = [string]$check.status
            if ($counts.Contains($status)) { $counts[$status]++ } else { $counts['error']++ }
        }
    }
    $Run.summary = $counts
    return $counts
}

if ($ApplyVerdicts) {
    # Merge a person's verdicts on review checks: { "<patch name>/<check id>": { "status": "pass|fail", "note": "..." } }.
    if (-not $Results) { throw '-ApplyVerdicts needs -Results.' }
    $run = Get-Content -LiteralPath $Results -Raw | ConvertFrom-Json
    $verdicts = Get-Content -LiteralPath $ApplyVerdicts -Raw | ConvertFrom-Json
    $applied = 0
    foreach ($property in $verdicts.PSObject.Properties) {
        $patchName, $checkId = $property.Name -split '/', 2
        $patch = $run.patches | Where-Object { $_.name -eq $patchName } | Select-Object -First 1
        $check = if ($patch) { $patch.checks | Where-Object { $_.id -eq $checkId } | Select-Object -First 1 }
        if (-not $check) { Write-Warning "no check $($property.Name) in the results"; continue }
        $check.status = $property.Value.status
        $check | Add-Member -NotePropertyName verdictNote -NotePropertyValue $property.Value.note -Force
        $applied++
    }
    $counts = Write-Summary $run
    $run | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Results -Encoding utf8
    Write-Host "[audit] $applied verdicts merged: $(($counts.GetEnumerator() | ForEach-Object { "$($_.Key) $($_.Value)" }) -join ', ')"
    exit 0
}

if ($Validate) {
    # The catalog against the source it describes: every patch in patches-list.json has one entry,
    # every step is one the runner knows, and every probe action and setting key still exists.
    $problems = New-Object System.Collections.Generic.List[string]
    $entries = @(Get-Content -LiteralPath $Catalog -Raw | ConvertFrom-Json)
    $declared = @((Get-Content -LiteralPath (Join-Path $root 'patches-list.json') -Raw | ConvertFrom-Json).patches | ForEach-Object { [string]$_.name })
    $named = @($entries | ForEach-Object { [string]$_.name })
    foreach ($name in $declared) { if ($named -notcontains $name) { $problems.Add("no entry for the patch '$name'") } }
    foreach ($name in $named) { if ($declared -notcontains $name) { $problems.Add("'$name' is not a patch in patches-list.json") } }
    foreach ($twice in @($named | Group-Object | Where-Object Count -gt 1)) { $problems.Add("'$($twice.Name)' has $($twice.Count) entries") }
    $probeSource = Get-Content -LiteralPath (Join-Path $root 'tools/verification-probe/src/app/hushfeed/verification/Probe.java') -Raw
    $actions = @([regex]::Matches($probeSource, 'case "([a-z0-9-]+)"\s*:') | ForEach-Object { $_.Groups[1].Value })
    $keys = New-Object System.Collections.Generic.HashSet[string]
    Get-ChildItem -Path (Join-Path $root 'extensions') -Recurse -Filter *.java | Where-Object { $_.FullName -match '[\\/]src[\\/]main[\\/]' } | ForEach-Object {
        foreach ($found in [regex]::Matches((Get-Content -LiteralPath $_.FullName -Raw), 'Setting\(\s*"([A-Za-z0-9_]+)"')) { [void]$keys.Add($found.Groups[1].Value) }
    }
    $stepKinds = 'set', 'restart', 'open', 'swipe', 'wait', 'tap', 'longpress', 'back', 'probe', 'log', 'logNot', 'shot', 'apk', 'night'
    $apkKinds = 'entry-empty', 'entry-absent', 'entry-present', 'manifest', 'resources', 'icon-xml', 'dex-string'
    foreach ($entry in $entries) {
        $where = "'$($entry.name)'"
        if ($entry.kind -notin 'runtime', 'static', 'infrastructure') { $problems.Add("$where has kind '$($entry.kind)'") }
        if (@($entry.checks).Count -eq 0) { $problems.Add("$where has no checks") }
        foreach ($setting in @($entry.settings)) { if ($setting.key -and -not $keys.Contains([string]$setting.key)) { $problems.Add("$where lists the unknown setting $($setting.key)") } }
        foreach ($check in @($entry.checks)) {
            $at = "$where/$($check.id)"
            $index = 0
            foreach ($step in @($check.steps)) {
                $index++
                $names = @($step.PSObject.Properties.Name)
                $kind = $names[0]
                $bad = { param($why) $problems.Add("$at step $index`: $why") }
                if ($kind -notin $stepKinds) { & $bad "unknown step '$kind'"; continue }
                switch ($kind) {
                    'set' { foreach ($property in $step.set.PSObject.Properties) { if (-not $keys.Contains($property.Name)) { & $bad "sets the unknown key $($property.Name)" } } }
                    'probe' { if ($actions -notcontains [string]$step.probe) { & $bad "the probe has no action '$($step.probe)'" } }
                    'open' {
                        $surface = [string]$step.open
                        if ($surface -notin 'feed', 'profile', 'inbox', 'friends', 'settings', 'appinfo' -and $surface -notlike 'search:*' -and $surface -notlike 'url:*') { & $bad "can't open '$surface'" }
                    }
                    'swipe' { if ([string]$step.swipe -notin 'up', 'down', 'left', 'right') { & $bad "swipes '$($step.swipe)'" } }
                    'night' { if ([string]$step.night -notin 'yes', 'no') { & $bad "sets night mode '$($step.night)'" } }
                    { $_ -in 'tap', 'longpress' } { $target = $step.$kind; if (-not ($target.text -or $target.desc -or $target.id)) { & $bad "a $kind with no text, desc or id" } }
                    'apk' { if ([string]$step.apk -notin $apkKinds) { & $bad "unknown apk check '$($step.apk)'" } }
                    'shot' { if (-not $step.review) { & $bad 'a shot with no review text' } }
                }
                foreach ($field in 'expect', 'expectNot', 'log', 'logNot') {
                    $pattern = [string]$step.$field
                    if ($pattern -and $step.apk -ne 'dex-string') {
                        try { [void][regex]::new($pattern) } catch { & $bad "$field is not a valid regex: $pattern" }
                    }
                }
            }
            $decides = @($check.steps | Where-Object { $_.expect -or $_.expectNot -or $_.log -or $_.logNot -or $_.apk -or $_.shot }).Count
            if ($decides -eq 0) { $problems.Add("$at decides nothing: no expect, log, apk or shot step") }
            $reviews = @($check.steps | Where-Object { $_.shot }).Count
            if ($check.auto -and $reviews -gt 0) { $problems.Add("$at is marked auto but has a shot") }
        }
    }
    if ($problems.Count -gt 0) { $problems | ForEach-Object { Write-Host "  $_" }; Write-Host "[audit] the catalog has $($problems.Count) problems"; exit 1 }
    $checkCount = @($entries | ForEach-Object { $_.checks }).Count
    Write-Host "[audit] the catalog is sound: $($entries.Count) patches, $checkCount checks"
    exit 0
}

if (-not $Apk -or -not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw 'Pass -Apk with the patched APK under test.' }
$Apk = (Resolve-Path -LiteralPath $Apk).Path

function Resolve-AuditAdb {
    if ($Adb) { return $Adb }
    if ($env:HUSHFEED_ADB) { return $env:HUSHFEED_ADB }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $sdkAdb) { return $sdkAdb }
    throw 'No adb found. Pass -Adb or set HUSHFEED_ADB.'
}

function Resolve-AuditAapt2 {
    if ($Aapt2) { return $Aapt2 }
    if ($env:HUSHFEED_AAPT2) { return $env:HUSHFEED_AAPT2 }
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    # Newest by version number: as text, 9.0.0 sorts above 37.0.0.
    $tools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d+(\.\d+)*$' -and (Test-Path (Join-Path $_.FullName 'aapt2.exe')) } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $tools) { throw 'No aapt2 found. Pass -Aapt2 or set HUSHFEED_AAPT2.' }
    return Join-Path $tools.FullName 'aapt2.exe'
}

$aapt2Exe = Resolve-AuditAapt2
$catalogEntries = @(Get-Content -LiteralPath $Catalog -Raw | ConvertFrom-Json)
$catalogOrder = @{}
for ($i = 0; $i -lt $catalogEntries.Count; $i++) { $catalogOrder[[string]$catalogEntries[$i].name] = $i }
if ($Only) {
    $wanted = $Only -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    $catalogEntries = @($catalogEntries | Where-Object {
        $entry = $_
        @($wanted | Where-Object { $_ -eq $entry.name -or ($_ -like 'category:*' -and $_.Substring(9) -eq $entry.category) }).Count -gt 0
    })
    if ($catalogEntries.Count -eq 0) { throw "Nothing in the catalog matches -Only $Only." }
}

# What the APK is, read once.
$badging = & $aapt2Exe dump badging $Apk 2>$null
$versionName = if ("$badging" -match "versionName='([^']+)'") { $Matches[1] } else { 'unknown' }
$versionCode = if ("$badging" -match "versionCode='([^']+)'") { $Matches[1] } else { 'unknown' }
if (-not $Results) { $Results = Join-Path $PSScriptRoot "results\tiktok-$versionName.json" }
$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
if (-not $OutDir) { $OutDir = Join-Path ([IO.Path]::GetTempPath()) "hushfeed-audit\$stamp" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Results) | Out-Null

# APK readers. The manifest and resource dumps are cached, and every dex string the catalog
# asks for is looked up in one pass over the dex files instead of once per check.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkZip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
$apkCache = @{}
function Get-ApkDump([string]$Kind) {
    if (-not $apkCache.ContainsKey($Kind)) {
        $apkCache[$Kind] = if ($Kind -eq 'manifest') {
            (& $aapt2Exe dump xmltree --file AndroidManifest.xml $Apk 2>&1) -join "`n"
        } else {
            (& $aapt2Exe dump resources $Apk 2>&1) -join "`n"
        }
    }
    return $apkCache[$Kind]
}
# The adaptive icon the launcher shows, as aapt2 prints it. TikTok's resource names and file paths
# are obfuscated per build (mipmap/c in res/ar/a.xml on 47.1.4), so the file is found the way
# Android finds it: the application's android:icon id, then that resource's anydpi-v26 file.
function Get-LauncherIconXml {
    if (-not $apkCache.ContainsKey('icon-xml')) {
        $manifest = Get-ApkDump 'manifest'
        $application = $manifest.Substring([Math]::Max(0, $manifest.IndexOf('E: application')))
        if ($application -notmatch 'android:icon\(0x01010002\)=@(0x[0-9a-f]+)') { throw 'the manifest names no application icon' }
        $iconId = $Matches[1]
        $table = Get-ApkDump 'resources'
        $block = [regex]::Match($table, "(?ms)^\s*resource $iconId \S+\s*\n(.*?)(?=^\s*resource 0x|\z)")
        if (-not $block.Success) { throw "the resource table has no $iconId" }
        $file = [regex]::Match($block.Groups[1].Value, '\(anydpi-v26\) \(file\) (\S+\.xml)')
        if (-not $file.Success) { throw "the icon $iconId has no adaptive (anydpi-v26) file" }
        $apkCache['icon-xml'] = (& $aapt2Exe dump xmltree --file $file.Groups[1].Value $Apk 2>&1) -join "`n"
    }
    return $apkCache['icon-xml']
}

$dexWanted = New-Object System.Collections.Generic.HashSet[string]
foreach ($entry in $catalogEntries) {
    foreach ($check in $entry.checks) {
        foreach ($step in $check.steps) { if ($step.apk -eq 'dex-string') { [void]$dexWanted.Add([string]$step.expect) } }
    }
}
$dexFound = New-Object System.Collections.Generic.HashSet[string]
if ($dexWanted.Count -gt 0) {
    $needles = @($dexWanted | ForEach-Object { , [Text.Encoding]::UTF8.GetBytes($_) })
    $names = @($dexWanted)
    foreach ($dexEntry in @($apkZip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })) {
        $stream = $dexEntry.Open()
        try {
            $memory = New-Object System.IO.MemoryStream
            $stream.CopyTo($memory)
            $bytes = $memory.ToArray()
        } finally { $stream.Dispose() }
        # Latin-1 maps each byte to one char, so IndexOf on the strings finds the byte run.
        $haystack = [Text.Encoding]::GetEncoding(28591).GetString($bytes)
        for ($n = 0; $n -lt $names.Count; $n++) {
            if ($dexFound.Contains($names[$n])) { continue }
            $needle = [Text.Encoding]::GetEncoding(28591).GetString($needles[$n])
            if ($haystack.IndexOf($needle, [StringComparison]::Ordinal) -ge 0) { [void]$dexFound.Add($names[$n]) }
        }
    }
}

# Device plumbing.
$adbExe = $null
$deviceUp = -not $NoDevice
if ($deviceUp) {
    if (-not $Serial) { throw 'Pass -Serial (or set HUSHFEED_DEVICE_SERIAL), or -NoDevice for the APK checks only.' }
    $adbExe = Resolve-AuditAdb
}
$leaseFile = $null
$leaseToken = [guid]::NewGuid().ToString('N')
function Grant-AuditLease {
    if (-not $LeaseDir) { return }
    $script:leaseFile = Join-Path $LeaseDir "$Serial.json"
    # Older lease writers call the token "token", newer ones "ownershipToken"; both are read.
    $body = [ordered]@{ schemaVersion = 1; serial = $Serial; project = 'hushfeed'; purpose = 'patch audit'; ownershipToken = $leaseToken
        acquiredUtc = [DateTime]::UtcNow.ToString('o'); expiresUtc = [DateTime]::UtcNow.AddMinutes(30).ToString('o') } | ConvertTo-Json
    if (Test-Path -LiteralPath $script:leaseFile) {
        $held = Get-Content -LiteralPath $script:leaseFile -Raw | ConvertFrom-Json
        $mine = ($held.ownershipToken, $held.token) -contains $leaseToken
        $expiry = [string]$held.expiresUtc
        if (-not $mine -and $expiry -and [DateTimeOffset]::Parse($expiry) -gt [DateTimeOffset]::UtcNow) {
            throw "The phone is leased by $($held.project) ($($held.purpose)) until $expiry."
        }
        Set-Content -LiteralPath $script:leaseFile -Value $body
        return
    }
    $stream = [IO.File]::Open($script:leaseFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try { $bytes = [Text.Encoding]::UTF8.GetBytes($body); $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
}
function Revoke-AuditLease {
    if ($script:leaseFile -and (Test-Path -LiteralPath $script:leaseFile)) {
        $held = Get-Content -LiteralPath $script:leaseFile -Raw | ConvertFrom-Json
        if ($held.ownershipToken -eq $leaseToken) { Remove-Item -LiteralPath $script:leaseFile -Force }
    }
}

function Invoke-AuditAdb { & $adbExe -s $Serial @args }
function ConvertTo-DeviceQuoted([string]$Text) { "'" + ($Text -replace "'", "'\''") + "'" }
function Invoke-DeviceShell([string]$Command) { Invoke-AuditAdb shell $Command }

# A line in logcat that later reads start from. The phone's clock comes back with it, so a read
# asks logcat only for what was logged since (-T) instead of pulling the whole buffer each time.
function Write-Marker {
    $id = "hushfeed-audit-$([guid]::NewGuid().ToString('N'))"
    $clock = "$(Invoke-DeviceShell "date '+%m-%d %H:%M:%S'; log -t HushfeedAudit $id")".Trim()
    return [pscustomobject]@{ id = $id; since = "$clock.000" }
}

# The lines after a marker, in "-v tag" form ("D/morphe: FeedFilter: dropped ..."): Hushfeed's
# logger names the class in the tag, and a raw read would lose it.
function Get-LogAfter($Marker, [string[]]$Tags) {
    # The marker's own tag always comes along: without its line there is nowhere to start.
    $filter = if ($Tags) { '-s ' + ((@($Tags) + 'HushfeedAudit' | Select-Object -Unique | ForEach-Object { "${_}:V" }) -join ' ') } else { '' }
    $lines = @(Invoke-DeviceShell "logcat -d -v tag -T $(ConvertTo-DeviceQuoted $Marker.since) $filter" | ForEach-Object { "$_".TrimEnd() })
    $at = -1
    # Only the marker's own line: adbd also logs the shell command that wrote it.
    $own = "HushfeedAudit\s*: $([regex]::Escape($Marker.id))$"
    for ($i = $lines.Count - 1; $i -ge 0; $i--) { if ($lines[$i] -match $own) { $at = $i; break } }
    if ($at -lt 0) { return @() }
    return @($lines | Select-Object -Skip ($at + 1))
}

# Catalog regexes are read line by line: ^ and $ anchor at each line of a multi-line answer.
function Test-Pattern([string]$Text, [string]$Pattern) { return [regex]::IsMatch($Text, $Pattern, 'IgnoreCase, Multiline') }

# One probe action: its whole answer as one string, the numbered pieces joined back together.
function Invoke-AuditProbe([string]$Action, $Extras, [int]$WaitMs = 3000, [string]$Until) {
    $marker = Write-Marker
    $parts = @("am broadcast -a app.hushfeed.verification.PROBE -p $package -e action $(ConvertTo-DeviceQuoted $Action)")
    if ($Extras) {
        foreach ($property in $Extras.PSObject.Properties) {
            $parts += "-e $(ConvertTo-DeviceQuoted $property.Name) $(ConvertTo-DeviceQuoted ([string]$property.Value))"
        }
    }
    Invoke-DeviceShell ($parts -join ' ') | Out-Null
    $done = if ($Until) { $Until } else { "^(ok|failed) $([regex]::Escape($Action))\b" }
    $deadline = [DateTime]::UtcNow.AddMilliseconds([Math]::Max($WaitMs, 800) + 4000)
    $answer = @()
    do {
        Start-Sleep -Milliseconds 400
        # "I/HushfeedProbe: text" opens each message; the message's other lines come bare.
        $answer = @(Get-LogAfter $marker @('HushfeedProbe') |
            ForEach-Object { "$_" -replace '^[VDIWEF]/HushfeedProbe\s*: ', '' })
        $finished = @($answer | Where-Object { "$_" -match $done }).Count -gt 0
    } until ($finished -or [DateTime]::UtcNow -gt $deadline)
    # "<action>[n] text" opens a piece; the lines after it belong to that piece. Pieces were cut
    # from one string, so they join with nothing between them.
    $pieces = New-Object System.Collections.Generic.List[string]
    $plain = New-Object System.Collections.Generic.List[string]
    $current = $null
    foreach ($line in $answer) {
        [string]$text = $line
        if ($text -match "^$([regex]::Escape($Action))\[\d+\] (.*)$") {
            if ($null -ne $current) { $pieces.Add($current) }
            $current = $Matches[1]
            continue
        }
        if ($null -ne $current -and $text -notmatch '^(ok|failed) ') { $current += "`n" + $text; continue }
        if ($null -ne $current) { $pieces.Add($current); $current = $null }
        $plain.Add($text)
    }
    if ($null -ne $current) { $pieces.Add($current) }
    return (($plain -join "`n") + "`n" + ($pieces -join '')).Trim()
}

function Get-ScreenSize {
    $size = "$(Invoke-DeviceShell 'wm size')"
    if ($size -match '(\d+)x(\d+)\s*$') { return @([int]$Matches[1], [int]$Matches[2]) }
    return @(1080, 2340)
}

function Get-Focus { "$(Invoke-DeviceShell 'dumpsys window' | Select-String 'mCurrentFocus' | Select-Object -Last 1)".Trim() }

function Restart-TikTok {
    $script:settingsOpen = $false
    Invoke-DeviceShell "am force-stop $package" | Out-Null
    Start-Sleep -Milliseconds 800
    $marker = Write-Marker
    Invoke-DeviceShell 'am instrument app.hushfeed.verification/.Probe' 2>&1 | Out-Null
    Invoke-DeviceShell "monkey -p $package -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(40)
    do {
        Start-Sleep -Milliseconds 700
        $ready = @(Get-LogAfter $marker @('HushfeedProbe') | Where-Object { "$_" -match 'HushfeedProbe\s*: ready$' }).Count -gt 0
    } until ($ready -or [DateTime]::UtcNow -gt $deadline)
    if (-not $ready) { throw 'the probe never came up after the restart (is it installed and signed like TikTok?)' }
    Start-Sleep -Seconds 7
}

# A tap on the node a fresh uiautomator dump finds. On Hushfeed's settings pages a row below the
# fold is scrolled to, a few screens at most. Elsewhere nothing scrolls unless the target asks
# ("scroll": n), since TikTok's lists run into suggested accounts.
$script:settingsOpen = $false
function Invoke-DumpTap($Target, [int]$HoldMs = 0) {
    $screens = if ($null -ne $Target.scroll) { [int]$Target.scroll } elseif ($script:settingsOpen) { 5 } else { 0 }
    for ($attempt = 0; ; $attempt++) {
        try { return Invoke-DumpTapOnce $Target $HoldMs }
        catch {
            if ($attempt -ge $screens -or $_.Exception.Message -notlike 'nothing on screen*') { throw }
            $w, $h = Get-ScreenSize
            Invoke-DeviceShell "input swipe $([int]($w / 2)) $([int]($h * 0.7)) $([int]($w / 2)) $([int]($h * 0.35)) 400" | Out-Null
            Start-Sleep -Milliseconds 900
        }
    }
}

# One dump and one tap (a press held for HoldMs when it is above 0), refusing anything that
# reaches other people.
function Invoke-DumpTapOnce($Target, [int]$HoldMs = 0) {
    Invoke-DeviceShell 'rm -f /sdcard/hushfeed-audit.xml' | Out-Null
    Invoke-DeviceShell 'uiautomator dump /sdcard/hushfeed-audit.xml' | Out-Null
    $xml = (Invoke-DeviceShell 'cat /sdcard/hushfeed-audit.xml') -join ''
    Invoke-DeviceShell 'rm -f /sdcard/hushfeed-audit.xml' | Out-Null
    if ($xml -notlike '*<hierarchy*') { throw 'uiautomator could not dump the screen (a playing video blocks it)' }
    $attribute, $pattern = if ($Target.text) { 'text', $Target.text } elseif ($Target.desc) { 'content-desc', $Target.desc } else { 'resource-id', $Target.id }
    foreach ($node in [regex]::Matches($xml, '<node [^>]*>')) {
        # Without invisible format characters: TikTok wraps numbers in direction marks, so a
        # pattern anchored at a digit ("^3d") never matched its text.
        $value = if ($node.Value -match " $attribute=`"([^`"]*)`"") { [Net.WebUtility]::HtmlDecode($Matches[1]) -replace '\p{Cf}', '' } else { '' }
        if ($value -notmatch $pattern) { continue }
        $label = @('text', 'content-desc') | ForEach-Object { if ($node.Value -match " $_=`"([^`"]*)`"") { [Net.WebUtility]::HtmlDecode($Matches[1]) -replace '\p{Cf}', '' } }
        if (@($label | Where-Object { $_ -and $_.Trim().ToLowerInvariant() -match $outwardWords }).Count -gt 0) {
            throw "refused to tap '$($label -join ' / ')': it reaches other people"
        }
        if ($node.Value -notmatch 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') { continue }
        $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
        $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
        if ($HoldMs -gt 0) {
            Invoke-DeviceShell "input swipe $x $y $x $y $HoldMs" | Out-Null
            return "held $attribute ~ $pattern at $x,$y for $HoldMs ms"
        }
        Invoke-DeviceShell "input tap $x $y" | Out-Null
        return "tapped $attribute ~ $pattern at $x,$y"
    }
    throw "nothing on screen has $attribute matching $pattern"
}

function Open-Surface([string]$Where) {
    $script:settingsOpen = $Where -eq 'settings'
    $tabs = @{ feed = '^Home'; friends = '^Friends'; inbox = '^Inbox'; profile = '^Profile' }
    if ($tabs.ContainsKey($Where)) {
        # The tabs live on the main screen. Anything else in front (a search page a deep link
        # left on the back stack, a settings page, another app) costs a fresh start.
        if ((Get-Focus) -notmatch 'com\.ss\.android\.ugc\.aweme\.main\.MainActivity') { Restart-TikTok }
        $answer = Invoke-AuditProbe 'click' ([pscustomobject]@{ desc = $tabs[$Where] }) 1500
        if (-not (Test-Pattern $answer '^ok click')) { throw "could not open $Where`: $answer" }
    } elseif ($Where -eq 'settings') {
        $answer = Invoke-AuditProbe 'settings' $null 2500
        if (-not (Test-Pattern $answer '^ok settings')) { throw "could not open Hushfeed settings: $answer" }
    } elseif ($Where -like 'search:*') {
        $keyword = [uri]::EscapeDataString($Where.Substring(7))
        Invoke-DeviceShell "am start -a android.intent.action.VIEW -d $(ConvertTo-DeviceQuoted "snssdk1233://search?keyword=$keyword") -p $package" | Out-Null
    } elseif ($Where -like 'url:*') {
        Invoke-DeviceShell "am start -a android.intent.action.VIEW -d $(ConvertTo-DeviceQuoted $Where.Substring(4)) -p $package" | Out-Null
    } elseif ($Where -eq 'appinfo') {
        # Android's App info page for TikTok: the name and icon the launcher shows, on a page that
        # is the same on every phone, where the home screen's layout isn't.
        Invoke-DeviceShell "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$package" | Out-Null
    } else {
        throw "unknown surface $Where"
    }
    Start-Sleep -Seconds 3
}

# The results file is committed, so it names evidence by file name inside -OutDir and never by
# a path on the machine that ran it.
function Save-Shot([string]$Name) {
    $file = "$($Name -replace '[^\w.-]', '_').png"
    Invoke-DeviceShell 'screencap -p /sdcard/hushfeed-audit.png' | Out-Null
    Invoke-AuditAdb pull /sdcard/hushfeed-audit.png (Join-Path $OutDir $file) 2>&1 | Out-Null
    Invoke-DeviceShell 'rm -f /sdcard/hushfeed-audit.png' | Out-Null
    return $file
}

function Save-Evidence([string]$Name, [string]$Text) {
    $file = "$($Name -replace '[^\w.-]', '_').txt"
    Set-Content -LiteralPath (Join-Path $OutDir $file) -Value $Text -Encoding utf8
    return $file
}

function ConvertTo-LeafName([string]$Value) {
    if ($Value -match '^[A-Za-z]:\\') { return Split-Path -Leaf $Value }
    return $Value
}

# One check: every step in order, the first failed expectation ends it.
$script:nightWas = $null
# Puts back the phone's night mode a night step changed. Returns whether it changed anything.
function Restore-NightMode {
    if ($null -eq $script:nightWas) { return $false }
    try { Invoke-DeviceShell "cmd uimode night $($script:nightWas)" | Out-Null }
    catch { Write-Warning "could not put the phone's night mode back to $($script:nightWas): $($_.Exception.Message)" }
    $script:nightWas = $null
    return $true
}

function Invoke-Check($Entry, $Check, [hashtable]$Originals, [ref]$Restarted) {
    $result = [ordered]@{ id = $Check.id; what = $Check.what; auto = [bool]$Check.auto; status = 'pass'; failures = @(); evidence = @(); review = @() }
    $tag = "$($Entry.name)-$($Check.id)"
    $index = 0
    $lastMarker = $null
    foreach ($step in $Check.steps) {
        $index++
        $kind = @($step.PSObject.Properties.Name)[0]
        try {
            # A log step reads what was logged from the start of the step before it, so the
            # lines a swipe or a restart causes are in its window.
            if ($deviceUp -and $kind -notin 'apk', 'log', 'logNot') { $lastMarker = Write-Marker }
            if ($kind -eq 'apk') {
                $path = [string]$step.path
                switch ([string]$step.apk) {
                    'entry-empty' {
                        $zipEntry = $apkZip.GetEntry($path)
                        if (-not $zipEntry) { throw "$path is not in the APK" }
                        if ($zipEntry.Length -ne 0) { throw "$path is $($zipEntry.Length) bytes, not empty" }
                    }
                    'entry-present' { if (-not $apkZip.GetEntry($path)) { throw "$path is not in the APK" } }
                    'entry-absent' { if ($apkZip.GetEntry($path)) { throw "$path is still in the APK" } }
                    'dex-string' { if (-not $dexFound.Contains([string]$step.expect)) { throw "no dex holds the string '$($step.expect)'" } }
                    { $_ -in 'manifest', 'resources', 'icon-xml' } {
                        $dump = if ($step.apk -eq 'icon-xml') { Get-LauncherIconXml } else { Get-ApkDump $step.apk }
                        if ($step.expect -and -not (Test-Pattern $dump $step.expect)) { throw "the $($step.apk) has nothing matching $($step.expect)" }
                        if ($step.expectNot -and (Test-Pattern $dump $step.expectNot)) { throw "the $($step.apk) still matches $($step.expectNot)" }
                    }
                    default { throw "unknown apk check $($step.apk)" }
                }
                continue
            }
            if (-not $deviceUp) { $result.status = 'skipped'; $result.failures += 'needs a phone'; return $result }
            switch ($kind) {
                'set' {
                    foreach ($property in $step.set.PSObject.Properties) {
                        if (-not $Originals.ContainsKey($property.Name)) {
                            $was = Invoke-AuditProbe 'get' ([pscustomobject]@{ key = $property.Name }) 1200
                            if ($was -notmatch "ok get $([regex]::Escape($property.Name))=(.*)") { throw "could not read $($property.Name): $was" }
                            $Originals[$property.Name] = $Matches[1].Trim()
                        }
                        $answer = Invoke-AuditProbe 'set' ([pscustomobject]@{ key = $property.Name; value = [string]$property.Value }) 1200
                        if ($answer -notmatch "ok set $([regex]::Escape($property.Name)) ") { throw "could not set $($property.Name): $answer" }
                    }
                    # The write goes to disk asynchronously, and a restart straight after can lose it.
                    # A live setting is read on its next use, so only a restart waits.
                    $next = @($Check.steps)[$index]
                    Start-Sleep -Milliseconds $(if ($next -and $next.restart) { 2500 } else { 300 })
                }
                'restart' { Restart-TikTok; $Restarted.Value = $true }
                'open' { Open-Surface ([string]$step.open) }
                'swipe' {
                    $w, $h = Get-ScreenSize
                    $x = [int]($w / 2); $top = [int]($h * 0.28); $bottom = [int]($h * 0.72); $left = [int]($w * 0.2); $right = [int]($w * 0.8); $mid = [int]($h / 2)
                    $move = switch ([string]$step.swipe) {
                        'up' { "$x $bottom $x $top" } 'down' { "$x $top $x $bottom" }
                        'left' { "$right $mid $left $mid" } 'right' { "$left $mid $right $mid" }
                        default { throw "unknown swipe $($step.swipe)" }
                    }
                    Invoke-DeviceShell "input swipe $move 220" | Out-Null
                    Start-Sleep -Milliseconds 1800
                }
                'wait' { Start-Sleep -Milliseconds ([int]$step.wait) }
                'night' {
                    # The phone's dark theme, which TikTok follows. The phone's own mode goes back
                    # once the patch's checks are done.
                    if ($null -eq $script:nightWas) {
                        [string]$now = Invoke-DeviceShell 'cmd uimode night'
                        if ([string]::IsNullOrEmpty($now) -or $now -notmatch 'Night mode:\s*(\w+)') { throw "could not read the phone's night mode: $now" }
                        $script:nightWas = $Matches[1]
                    }
                    Invoke-DeviceShell "cmd uimode night $($step.night)" | Out-Null
                    Start-Sleep -Milliseconds 1500
                }
                'back' { Invoke-DeviceShell 'input keyevent KEYCODE_BACK' | Out-Null; Start-Sleep -Milliseconds 1200 }
                'tap' { $result.evidence += (Invoke-DumpTap $step.tap); Start-Sleep -Milliseconds 1500 }
                'longpress' { $result.evidence += (Invoke-DumpTap $step.longpress 900); Start-Sleep -Milliseconds 1500 }
                'probe' {
                    $wait = if ($step.waitMs) { [int]$step.waitMs } else { 3000 }
                    # "retrySwipe": n moves the feed on to the next video up to n times until the
                    # answer matches, for a check that needs a certain kind of video in front.
                    $swipesLeft = if ($step.retrySwipe) { [int]$step.retrySwipe } else { 0 }
                    # Every attempt's answer goes into the one evidence file, so a precondition
                    # that never held shows each video it looked at.
                    $attempts = @()
                    try {
                        while ($true) {
                            $answer = Invoke-AuditProbe ([string]$step.probe) $step.extras $wait ([string]$step.until)
                            $attempts += $answer
                            if (Test-Pattern $answer "^failed $([regex]::Escape([string]$step.probe))") {
                                throw "probe $($step.probe) failed: $((($answer -split "`n") | Where-Object { $_ -match '^failed |Exception' } | Select-Object -First 2) -join ' ')"
                            }
                            if (-not $answer) { throw "probe $($step.probe) never answered" }
                            $miss = if ($step.expect -and -not (Test-Pattern $answer $step.expect)) { "probe $($step.probe) answer has nothing matching $($step.expect)" }
                                elseif ($step.expectNot -and (Test-Pattern $answer $step.expectNot)) { "probe $($step.probe) answer matches $($step.expectNot)" }
                            if (-not $miss) { break }
                            if ($swipesLeft -le 0) {
                                # A precondition that never held says nothing about the patch.
                                if ($step.precondition) { throw [System.InvalidOperationException]::new("precondition: $miss") }
                                throw $miss
                            }
                            $swipesLeft--
                            $w, $h = Get-ScreenSize
                            Invoke-DeviceShell "input swipe $([int]($w / 2)) $([int]($h * 0.72)) $([int]($w / 2)) $([int]($h * 0.28)) 220" | Out-Null
                            Start-Sleep -Milliseconds 2200
                        }
                    } finally {
                        $result.evidence += Save-Evidence "$tag-$index-$($step.probe)" ($attempts -join "`n--- next video`n")
                    }
                }
                { $_ -in 'log', 'logNot' } {
                    $within = if ($step.within) { [int]$step.within } else { 8000 }
                    if (-not $lastMarker) { $lastMarker = Write-Marker }
                    $pattern = [string]$step.$kind
                    # A log step waits until its line shows up or its window closes; logNot waits the whole window.
                    $deadline = [DateTime]::UtcNow.AddMilliseconds($within)
                    do {
                        Start-Sleep -Milliseconds 1000
                        $hits = @(Get-LogAfter $lastMarker | Where-Object { Test-Pattern "$_" $pattern })
                    } until (($kind -eq 'log' -and $hits.Count -gt 0) -or [DateTime]::UtcNow -gt $deadline)
                    if ($kind -eq 'log' -and $hits.Count -eq 0) { throw "no log line matching $pattern" }
                    if ($kind -eq 'logNot' -and $hits.Count -gt 0) { throw "logged: $($hits[0])" }
                    if ($hits.Count -gt 0) { $result.evidence += Save-Evidence "$tag-$index-log" ($hits -join "`n") }
                }
                'shot' {
                    $file = Save-Shot "$tag-$($step.shot)"
                    $result.evidence += $file
                    $result.review += [ordered]@{ shot = $file; criteria = [string]$step.review }
                    if ($result.status -eq 'pass') { $result.status = 'review' }
                }
                default { throw "unknown step $kind" }
            }
        } catch {
            $result.status = if ($_.Exception.Message -like 'precondition: *') { 'inconclusive' } else { 'fail' }
            $result.failures += "step $index ($kind): $($_.Exception.Message)"
            if ($deviceUp -and $kind -ne 'apk') {
                try { $result.evidence += Save-Shot "$tag-failed-step$index" } catch { }
                # A check that stopped halfway leaves a sheet, a page or a settings screen open.
                # The next check starts from the feed of a fresh TikTok instead.
                try { Restart-TikTok; $Restarted.Value = $true } catch { $result.failures += "restart after the failure: $($_.Exception.Message)" }
            }
            return $result
        }
    }
    return $result
}

$run = [ordered]@{
    app = [ordered]@{ package = $package; version = $versionName; versionCode = $versionCode }
    apk = [ordered]@{ file = (Split-Path -Leaf $Apk); sha256 = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant() }
    hushfeed = [ordered]@{ commit = "$(git -C $root rev-parse --short HEAD 2>$null)"; catalog = (Get-FileHash -LiteralPath $Catalog -Algorithm SHA256).Hash.Substring(0, 16).ToLowerInvariant() }
    device = $null
    startedUtc = [DateTime]::UtcNow.ToString('o')
    finishedUtc = $null
    evidence = (Split-Path -Leaf $OutDir)
    summary = $null
    hooks = $null
    patches = @()
}
# A part rerun (-Only) keeps what the version's results file already says about the other
# patches. Each patch's result names the APK it ran against, so a carried one stays honest.
if ($Only -and (Test-Path -LiteralPath $Results)) {
    $previous = Get-Content -LiteralPath $Results -Raw | ConvertFrom-Json
    $rerun = @($catalogEntries | ForEach-Object { [string]$_.name })
    $run.patches = @($previous.patches | Where-Object { $rerun -notcontains $_.name })
    foreach ($kept in $run.patches) {
        $kept.evidence = ConvertTo-LeafName $kept.evidence
        foreach ($keptCheck in $kept.checks) {
            $keptCheck.evidence = @($keptCheck.evidence | ForEach-Object { ConvertTo-LeafName $_ })
            foreach ($shot in $keptCheck.review) { $shot.shot = ConvertTo-LeafName $shot.shot }
        }
    }
    $run.startedUtc = $previous.startedUtc
    $run.hooks = $previous.hooks
}
function Save-Results {
    $run.patches = @($run.patches | Sort-Object { if ($catalogOrder.ContainsKey([string]$_.name)) { $catalogOrder[[string]$_.name] } else { 9999 } })
    $run.finishedUtc = [DateTime]::UtcNow.ToString('o')
    [void](Write-Summary $run)
    $run | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Results -Encoding utf8
}

$runOriginals = [ordered]@{}
try {
    if ($deviceUp) {
        Grant-AuditLease
        $run.device = [ordered]@{
            serial = $Serial
            model = "$(Invoke-DeviceShell 'getprop ro.product.model')".Trim()
            android = "$(Invoke-DeviceShell 'getprop ro.build.version.release')".Trim()
        }
        Invoke-DeviceShell 'input keyevent KEYCODE_WAKEUP' | Out-Null
        if ($Install) {
            Write-Host "[audit] installing $Apk"
            Invoke-AuditAdb install -r $Apk | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'adb install failed; is the APK signed with the key of the TikTok on the phone?' }
        }
        $installed = "$(Invoke-DeviceShell "dumpsys package $package" | Select-String 'versionName=' | Select-Object -First 1)".Trim()
        if ($installed -notmatch [regex]::Escape("versionName=$versionName")) { Write-Warning "the phone has $installed, the APK is $versionName" }
        Restart-TikTok
        # For the whole run: Hushfeed not paused, Log diagnostics on for the log steps, and no
        # error toasts over the screenshots. The phone's own values go back at the end.
        foreach ($pair in ([ordered]@{ hushfeed_paused = 'false'; morphe_debug = 'true'; morphe_debug_toast_on_error = 'false' }).GetEnumerator()) {
            $was = Invoke-AuditProbe 'get' ([pscustomobject]@{ key = $pair.Key }) 1200
            if ($was -notmatch "ok get $($pair.Key)=(.*)") { throw "could not read $($pair.Key): $was" }
            $runOriginals[$pair.Key] = $Matches[1].Trim()
            [void](Invoke-AuditProbe 'set' ([pscustomobject]@{ key = $pair.Key; value = $pair.Value }) 1200)
        }
        Start-Sleep -Seconds 3
        Restart-TikTok
    }
    $count = 0
    foreach ($entry in $catalogEntries) {
        $count++
        Write-Host "[audit] $count/$($catalogEntries.Count) $($entry.name)"
        if ($deviceUp) { Grant-AuditLease }
        $originals = @{}
        $restarted = $false
        $checks = @()
        foreach ($check in $entry.checks) {
            $checkResult = Invoke-Check $entry $check $originals ([ref]$restarted)
            Write-Host "  $($checkResult.status.PadRight(7)) $($check.id)$(if ($checkResult.failures) { ': ' + ($checkResult.failures -join '; ') })"
            $checks += [pscustomobject]$checkResult
        }
        # A patch that crashed TikTok or left it in another app must not cost the next one its start.
        if ($deviceUp -and (Get-Focus) -notmatch [regex]::Escape($package)) { Restart-TikTok; $restarted = $false }
        if ($deviceUp -and $originals.Count -gt 0) {
            foreach ($key in $originals.Keys) {
                $answer = Invoke-AuditProbe 'set' ([pscustomobject]@{ key = $key; value = $originals[$key] }) 1200
                if ($answer -notmatch "ok set $([regex]::Escape($key)) ") { Write-Warning "could not put $key back to $($originals[$key]): $answer" }
            }
            Start-Sleep -Seconds 3
            if ($restarted) { Restart-TikTok }
        }
        if ($deviceUp -and (Restore-NightMode)) { Start-Sleep -Seconds 2; Restart-TikTok }
        $run.patches = @($run.patches) + [pscustomobject][ordered]@{ name = $entry.name; category = $entry.category; kind = $entry.kind
            ranUtc = [DateTime]::UtcNow.ToString('o'); apkSha256 = $run.apk.sha256; evidence = $run.evidence; checks = $checks }
        # Written after every patch, so a run cut short still leaves what it found.
        Save-Results
    }
    if ($deviceUp) {
        # Every family of lookups the run woke up, and the ones that found nothing. A miss here is
        # a renamed view or member, whatever check it belongs to, so it is the first thing to read
        # after a retarget.
        if ((Get-Focus) -notmatch [regex]::Escape($package)) { Restart-TikTok }
        $hookAnswer = Invoke-AuditProbe 'hookdetails' $null 2500
        [void](Save-Evidence 'hookdetails' $hookAnswer)
        $families = @($hookAnswer -split "`n" | Where-Object { $_ -match '^hookdetail ' })
        $run.hooks = [ordered]@{
            families = $families.Count
            missed = @($families | Where-Object { $_ -match ' missed=\[' } | ForEach-Object { $_.Substring(11) })
        }
        if ($run.hooks.missed.Count -gt 0) { Write-Host '[audit] hook families with misses:'; $run.hooks.missed | ForEach-Object { Write-Host "  $_" } }
    }
} finally {
    $apkZip.Dispose()
    if ($deviceUp) {
        [void](Restore-NightMode)
        if ($runOriginals.Count -gt 0) {
            try {
                Restart-TikTok
                foreach ($key in $runOriginals.Keys) {
                    [void](Invoke-AuditProbe 'set' ([pscustomobject]@{ key = $key; value = $runOriginals[$key] }) 1200)
                }
                Start-Sleep -Seconds 3
            } catch {
                Write-Warning "could not put the phone's settings back ($($_.Exception.Message)): $(($runOriginals.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', ')"
            }
        }
        try { Invoke-DeviceShell "am force-stop $package" | Out-Null } catch { }
        Revoke-AuditLease
    }
}
Save-Results
$counts = $run.summary
Write-Host "[audit] $(($counts.GetEnumerator() | ForEach-Object { "$($_.Key) $($_.Value)" }) -join ', ')"
Write-Host "[audit] results $Results, evidence $OutDir"
if ($counts['fail'] -gt 0 -or $counts['error'] -gt 0) { exit 1 }
