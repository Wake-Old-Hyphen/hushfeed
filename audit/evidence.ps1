# Shared by execution, read-only result validation and human verdict import.
function ConvertTo-AuditJsonString([string]$Value) {
    # Windows PowerShell HTML-escapes characters that PS7 leaves literal. Own the encoding so
    # the same definition and persisted receipt keep their identity across both hosts.
    $escaped = $Value.Replace('\', '\\').Replace('"', '\"')
    $escaped = [regex]::Replace($escaped, '[\x00-\x1f\uD800-\uDFFF]', {
        param($match)
        '\u' + ([int][char]$match.Value).ToString('x4', [Globalization.CultureInfo]::InvariantCulture)
    })
    return '"' + $escaped + '"'
}

function Get-AuditCanonicalJson($Value) {
    if ($null -eq $Value) { return 'null' }
    # Scalars first. [pscustomobject] means PSObject, and any value a pipeline emitted is
    # wrapped in one, so a string tested that way serialized as {"Length":n}. -is looks through
    # the wrapper for every other type, PSCustomObject included.
    if ($Value -is [string] -or $Value -is [char]) { return ConvertTo-AuditJsonString ([string]$Value) }
    if ($Value -is [bool]) { return ([string]$Value).ToLowerInvariant() }
    $culture = [Globalization.CultureInfo]::InvariantCulture
    # ConvertFrom-Json reads 1.5 as decimal on Windows PowerShell and as double on PS7, so a
    # decimal prints as the double it stands for. Fixed digit counts, never 'R': Windows
    # PowerShell's 'R' falls back to 17 digits where PS7 prints the shortest round trip.
    if ($Value -is [decimal]) { $Value = [double]$Value }
    if ($Value -is [double] -or $Value -is [single]) {
        if ([double]::IsNaN($Value) -or [double]::IsInfinity($Value)) { throw 'Non-finite audit number.' }
        return $Value.ToString($(if ($Value -is [single]) { 'G9' } else { 'G17' }), $culture)
    }
    if ($Value -is [byte] -or $Value -is [sbyte] -or $Value -is [int16] -or $Value -is [uint16] -or
        $Value -is [int32] -or $Value -is [uint32] -or $Value -is [int64] -or $Value -is [uint64]) {
        return $Value.ToString($culture)
    }
    if ($Value -is [System.Collections.IDictionary]) {
        $keys = [string[]]@($Value.Keys)
        [Array]::Sort($keys, [StringComparer]::Ordinal)
        $parts = foreach ($key in $keys) {
            (ConvertTo-AuditJsonString ([string]$key)) + ':' + (Get-AuditCanonicalJson $Value[$key])
        }
        return '{' + ($parts -join ',') + '}'
    }
    if ($Value -is [System.Management.Automation.PSCustomObject]) {
        $keys = [string[]]@($Value.PSObject.Properties.Name)
        [Array]::Sort($keys, [StringComparer]::Ordinal)
        $parts = foreach ($key in $keys) {
            (ConvertTo-AuditJsonString $key) + ':' + (Get-AuditCanonicalJson $Value.PSObject.Properties[$key].Value)
        }
        return '{' + ($parts -join ',') + '}'
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        $items = foreach ($item in $Value) { Get-AuditCanonicalJson $item }
        return '[' + (@($items) -join ',') + ']'
    }
    throw "Unsupported audit value type: $($Value.GetType().FullName)"
}

function Get-AuditHash($Value) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash(
            [Text.Encoding]::UTF8.GetBytes((Get-AuditCanonicalJson $Value))))).Replace('-', '').ToLowerInvariant()
    } finally { $algorithm.Dispose() }
}

function Get-AuditDefinitionHash($Entry, $Check) {
    # Settings and patch classification can alter setup even if the check's own steps did not.
    return Get-AuditHash ([ordered]@{
        name = $Entry.name; category = $Entry.category; kind = $Entry.kind
        settings = @($Entry.settings); check = $Check
        stepKinds = @($Check.steps | ForEach-Object { @($_.PSObject.Properties.Name)[0] })
    })
}

function Get-AuditCatalogProblems($Entries) {
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $Entries) {
        if ([string]::IsNullOrWhiteSpace([string]$entry.name) -or ([string]$entry.name).Contains('/')) {
            "invalid patch name '$($entry.name)'"
        } elseif (-not $names.Add([string]$entry.name)) { "duplicate patch '$($entry.name)'" }
        $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($check in @($entry.checks)) {
            if ([string]::IsNullOrWhiteSpace([string]$check.id) -or ([string]$check.id).Contains('/')) {
                "invalid check ID '$($entry.name)/$($check.id)'"
            } elseif (-not $ids.Add([string]$check.id)) { "duplicate check '$($entry.name)/$($check.id)'" }
        }
    }
}

function Get-AuditEvidenceHashes($Evidence, [string]$Directory) {
    foreach ($file in @($Evidence)) {
        if (-not $Directory -or [string]::IsNullOrWhiteSpace([string]$file) -or
            [string]$file -cne [IO.Path]::GetFileName([string]$file) -or $file -in '.', '..') {
            throw 'Evidence must name a file inside -OutDir.'
        }
        [ordered]@{ file = [string]$file; sha256 = (Get-FileHash -LiteralPath (Join-Path $Directory $file) -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant() }
    }
}

function Set-AuditCheckReceipt($Result, [string]$Directory) {
    $Result.receipt = [ordered]@{
        execution = [Guid]::NewGuid().ToString('N'); definition = $Result.definitionHash
        apk = $Result.apkSha256; installed = $Result.installedApkSha256; build = $Result.build
        evidence = @($Result.evidence); files = @(Get-AuditEvidenceHashes $Result.evidence $Directory)
        review = @($Result.review); auto = $Result.auto; status = $Result.status; failures = @($Result.failures)
    }
    $Result.receiptId = Get-AuditHash $Result.receipt
}

function Test-AuditCheckBinding($Entry, $Check, $Result, [string]$ApkHash, [string]$Directory) {
    if ($Result.definitionHash -cne (Get-AuditDefinitionHash $Entry $Check)) { return $false }
    if ($ApkHash -notmatch '^[a-fA-F0-9]{64}$' -or $Result.apkSha256 -cne $ApkHash) { return $false }
    if ($Result.receiptId -notmatch '^[a-f0-9]{64}$' -or $Result.build.status -cne 'verified' -or
        $Result.build.receiptSha256 -notmatch '^[a-f0-9]{64}$') { return $false }
    $source = $Result.build.source
    if ($source.commit -notmatch '^[a-f0-9]{40}$' -or $source.clean -isnot [bool] -or -not $source.clean -or
        $source.startFingerprint -notmatch '^[a-fA-F0-9]{64}$' -or
        $source.startFingerprint -cne $source.endFingerprint) { return $false }
    $needsDevice = @($Check.steps | Where-Object { @($_.PSObject.Properties.Name)[0] -ne 'apk' }).Count -gt 0
    if ($needsDevice -and $Result.installedApkSha256 -cne $ApkHash) { return $false }
    $receipt = $Result.receipt
    if ($null -eq $receipt -or $receipt.execution -notmatch '^[a-f0-9]{32}$' -or
        (Get-AuditHash $receipt) -cne $Result.receiptId -or
        $receipt.definition -cne $Result.definitionHash -or $receipt.apk -cne $Result.apkSha256 -or
        $receipt.installed -cne $Result.installedApkSha256 -or $receipt.auto -ne $Result.auto -or
        (Get-AuditHash $receipt.build) -cne (Get-AuditHash $Result.build) -or
        (Get-AuditHash @($receipt.review)) -cne (Get-AuditHash @($Result.review)) -or
        (Get-AuditHash @($receipt.failures)) -cne (Get-AuditHash @($Result.failures)) -or
        (Get-AuditHash @($receipt.evidence)) -cne (Get-AuditHash @($Result.evidence))) { return $false }
    if ($Result.status -cne $receipt.status) {
        if ($receipt.status -cne 'review' -or $Result.auto -or $Check.auto -or
            $Result.status -cnotin @('pass', 'fail') -or $Result.verdict.status -cne $Result.status -or
            $Result.verdict.receiptId -cne $Result.receiptId -or
            [string]::IsNullOrWhiteSpace([string]$Result.verdict.note)) { return $false }
    }
    try { $files = @(Get-AuditEvidenceHashes $Result.evidence $Directory) } catch { return $false }
    if ((Get-AuditHash $files) -cne (Get-AuditHash @($receipt.files))) { return $false }
    foreach ($review in @($Result.review)) {
        if ([string]$review.shot -cnotin @($files | ForEach-Object { $_.file })) { return $false }
    }
    return $true
}

function Get-AuditResultValidation($Entries, $Run, [string]$Directory) {
    $expected = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $problems = [Collections.Generic.List[string]]::new()
    foreach ($problem in @(Get-AuditCatalogProblems $Entries)) { $problems.Add($problem) }
    foreach ($entry in $Entries) {
        foreach ($check in $entry.checks) { $expected["$($entry.name)/$($check.id)"] = @($entry, $check) }
    }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $missing = [Collections.Generic.List[string]]::new()
    $duplicates = [Collections.Generic.List[string]]::new()
    $removed = [Collections.Generic.List[string]]::new()
    $changed = [Collections.Generic.List[string]]::new()
    $unknown = [Collections.Generic.List[string]]::new()
    $historical = [Collections.Generic.List[string]]::new()
    $digests = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $current = 0
    $currentPass = 0
    foreach ($patch in @($Run.patches)) {
        foreach ($result in @($patch.checks)) {
            $key = "$($patch.name)/$($result.id)"
            $digest = if ($result.apkSha256) { [string]$result.apkSha256 } else { [string]$patch.apkSha256 }
            if ($digest) { [void]$digests.Add($digest) }
            if (-not $seen.Add($key)) { $duplicates.Add($key); continue }
            if (-not $expected.ContainsKey($key)) { $removed.Add($key); continue }
            if ($result.status -cnotin @('pass','fail','review','inconclusive','error','skipped')) { $unknown.Add($key); continue }
            $entry, $check = $expected[$key]
            if ($result.definitionHash -cne (Get-AuditDefinitionHash $entry $check)) { $changed.Add($key) }
            if (Test-AuditCheckBinding $entry $check $result ([string]$Run.apk.sha256) $Directory) {
                $current++
                if ($result.status -ceq 'pass') { $currentPass++ }
            } else { $historical.Add($key) }
        }
    }
    foreach ($key in $expected.Keys) { if (-not $seen.Contains($key)) { $missing.Add($key) } }
    foreach ($category in @(@('missing',$missing), @('duplicate',$duplicates), @('removed',$removed), @('changed-or-unversioned',$changed), @('unknown-status',$unknown))) {
        foreach ($key in $category[1]) { $problems.Add("$($category[0]): $key") }
    }
    foreach ($key in $historical) { $problems.Add("historical-or-unbound: $key") }
    if ($digests.Count -gt 1) { $problems.Add("mixed-build: results come from $($digests.Count) APKs") }
    return [pscustomobject]@{
        state = if ($problems.Count -eq 0) { 'current-bound' } else { 'historical-or-incomplete' }
        catalogChecks = $expected.Count; resultChecks = $seen.Count; currentChecks = $current; currentPass = $currentPass
        apkDigests = @($digests | Sort-Object); mixedBuild = $digests.Count -gt 1
        missing = @($missing | Sort-Object); duplicates = @($duplicates | Sort-Object); removed = @($removed | Sort-Object)
        changed = @($changed | Sort-Object); unknown = @($unknown | Sort-Object); historical = @($historical | Sort-Object)
        problems = @($problems)
    }
}

function ConvertFrom-AuditVerdictJson([string]$Json) {
    $value = ConvertFrom-Json -InputObject $Json -ErrorAction Stop
    if ($value -isnot [pscustomobject] -or -not $Json.TrimStart().StartsWith('{')) {
        throw 'Verdicts must be a JSON object keyed by patch/check ID.'
    }
    # ConvertFrom-Json discards earlier duplicate properties. Scan the original tokens before
    # accepting that object, including escaped names and duplicates within each verdict.
    $contexts = [Collections.Generic.Stack[object]]::new()
    $strings = [regex]'(?s)\G"(?:\\.|[^"\\])*"'
    for ($i = 0; $i -lt $Json.Length; $i++) {
        $character = $Json[$i]
        if ($character -eq "'" -or ($contexts.Count -gt 0 -and $contexts.Peek().keyExpected -and
                $character -ne '"' -and $character -ne '}' -and -not [char]::IsWhiteSpace($character))) {
            throw 'Verdict property names and strings must use JSON double quotes.'
        }
        if ($character -eq '"') {
            $token = $strings.Match($Json, $i)
            if (-not $token.Success) { throw 'Invalid JSON string in verdicts.' }
            if ($contexts.Count -gt 0 -and $contexts.Peek().keyExpected) {
                $context = $contexts.Peek()
                $decoded = ConvertFrom-Json -InputObject ('{' + $token.Value + ':null}')
                $key = @($decoded.PSObject.Properties.Name)[0]
                if (-not $context.keys.Add($key)) { throw "Duplicate verdict property '$key'." }
                $context.keyExpected = $false
            }
            $i += $token.Length - 1
        } elseif ($character -eq '{' -or $character -eq '[') {
            $contexts.Push([pscustomobject]@{
                isObject = $character -eq '{'; keyExpected = $character -eq '{'
                keys = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            })
        } elseif ($character -eq '}' -or $character -eq ']') {
            [void]$contexts.Pop()
        } elseif ($character -eq ',' -and $contexts.Count -gt 0) {
            $contexts.Peek().keyExpected = $contexts.Peek().isObject
        } elseif ($character -eq '/') {
            throw 'JSON comments are not allowed in verdict imports.'
        }
    }
    return $value
}

function Set-AuditVerdicts($Entries, $Run, $Verdicts, [string]$Directory) {
    $catalogProblems = @(Get-AuditCatalogProblems $Entries)
    if ($catalogProblems.Count) { throw ($catalogProblems -join '; ') }
    $updates = @()
    foreach ($property in $Verdicts.PSObject.Properties) {
        $name, $id = $property.Name -split '/', 2
        $entriesFound = @($Entries | Where-Object { $_.name -ceq $name })
        $patchesFound = @($Run.patches | Where-Object { $_.name -ceq $name })
        if ($entriesFound.Count -ne 1 -or $patchesFound.Count -ne 1) { throw "No unique patch for $($property.Name)." }
        $checksFound = @($entriesFound[0].checks | Where-Object { $_.id -ceq $id })
        $resultsFound = @($patchesFound[0].checks | Where-Object { $_.id -ceq $id })
        if ($checksFound.Count -ne 1 -or $resultsFound.Count -ne 1) { throw "No unique check for $($property.Name)." }
        $check = $checksFound[0]
        $result = $resultsFound[0]
        $verdict = $property.Value
        if ($result.status -cne 'review' -or $result.auto -or $check.auto -or @($result.review).Count -eq 0 -or
            @($check.steps | Where-Object { $_.shot }).Count -eq 0) { throw "$($property.Name) is not an undecided review check." }
        if ($verdict.status -cnotin @('pass','fail') -or [string]::IsNullOrWhiteSpace([string]$verdict.note)) {
            throw "$($property.Name) needs pass/fail and a review note."
        }
        if (-not (Test-AuditCheckBinding $entriesFound[0] $check $result ([string]$Run.apk.sha256) $Directory) -or
            $verdict.receiptId -cne $result.receiptId) { throw "$($property.Name) has stale or unbound evidence." }
        $updates += , @($result, $verdict)
    }
    # Validate the entire import before changing any result.
    foreach ($update in $updates) {
        $update[0].status = $update[1].status
        $update[0] | Add-Member -NotePropertyName verdictNote -NotePropertyValue $update[1].note -Force
        $update[0] | Add-Member -NotePropertyName verdict -NotePropertyValue ([pscustomobject]@{
            receiptId = $update[1].receiptId; status = $update[1].status; note = $update[1].note
        }) -Force
    }
}

function Read-AuditBuildIdentity([string]$ApkPath, [string]$ReceiptPath, [string]$BundlePath) {
    if (-not $ReceiptPath) { return [pscustomobject]@{ status = 'unbound'; reason = 'No device-build receipt supplied.' } }
    if (-not $BundlePath) { throw 'A build receipt requires -Bundle to verify its source manifest.' }
    . (Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts/release-receipt.ps1')
    $receipt = Get-Content -LiteralPath $ReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($receipt.schemaVersion -ne 1 -or $receipt.kind -cne 'hushfeed-device-build') { throw 'Unsupported device-build receipt.' }
    if ($receipt.apk.sha256 -ine (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash -or
        $receipt.bundle.sha256 -ine (Get-FileHash -LiteralPath $BundlePath -Algorithm SHA256).Hash) {
        throw 'The build receipt does not match the APK and bundle.'
    }
    $manifest = Get-BundleManifestFacts -BundlePath $BundlePath
    $sourceCheck = Test-BuildSourceFacts -Source $manifest.source -ExpectedCommit ([string]$receipt.source.commit)
    if (-not $sourceCheck.Valid -or (Get-AuditHash $manifest.source) -cne (Get-AuditHash $receipt.source)) {
        throw 'The build receipt source does not match a clean, unchanged bundle build.'
    }
    return [pscustomobject]@{ status = 'verified'; receiptSha256 = (Get-FileHash -LiteralPath $ReceiptPath -Algorithm SHA256).Hash.ToLowerInvariant(); source = $manifest.source }
}
