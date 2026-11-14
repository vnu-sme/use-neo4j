[CmdletBinding()]
param(
    [switch]$AllSnapshots,
    [switch]$EnableNeo4j,
    [switch]$ConfirmDisposableDatabase,
    [string]$EnvFile = '',
    [string]$OutputRoot = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N')
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $root 'ocl2cypher/target/experiments'
} else {
    $OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path
}
$runDir = Join-Path $OutputRoot $runId
$expectedComparisons = 135
$expectedRejections = 6
$expectedExclusions = 1
$expectedSnapshots = 8
$expectedInvariantEvaluations = 142
$expectedSourceOnly = 0
$profiles = @{shop=2; advanced=3; company=26; comprehensive=30; medical=36; carrental=25; families=10; persons=10}
if ($AllSnapshots) {
    $instances = @(Import-Csv -LiteralPath (Join-Path $root 'example/snapshot-manifest.tsv') -Delimiter "`t")
    foreach ($instance in $instances) {
        if ($instance.scope -eq 'USE_ONLY') { $expectedSourceOnly++; continue }
        if ($instance.scope -ne 'PIPELINE') { throw "Unknown snapshot scope: $($instance.scope)" }
        $profiles[$instance.profile] = [int]$instance.invariants
        $expectedSnapshots++
        $expectedInvariantEvaluations += [int]$instance.invariants
        $expectedRejections += [int]$instance.rejections
        $expectedExclusions += [int]$instance.exclusions
        $expectedComparisons += [int]$instance.invariants - [int]$instance.rejections - [int]$instance.exclusions
    }
}
$connectionKeys = @('NEO4J_URI', 'NEO4J_DB', 'NEO4J_USER', 'NEO4J_PASSWORD')
$savedConnections = @{}
foreach ($key in $connectionKeys) {
    $savedConnections[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$savedLive = [Environment]::GetEnvironmentVariable('OCL2CYPHER_EXPERIMENT_LIVE', 'Process')

function Import-ConnectionEnvironment([string]$Path) {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -notmatch '^\s*(NEO4J_URI|NEO4J_DB|NEO4J_USER|NEO4J_PASSWORD)\s*=(.*)$') {
            continue
        }
        $key = $Matches[1]
        $value = $Matches[2].Trim()
        if ($values.ContainsKey($key)) { throw "Duplicate connection key: $key" }
        if ($value.StartsWith('"') -or $value.StartsWith("'")) {
            if ($value.Length -lt 2 -or $value[$value.Length - 1] -ne $value[0]) {
                throw "Unclosed quote for $key"
            }
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ([string]::IsNullOrWhiteSpace($value)) { throw "Empty connection key: $key" }
        $values[$key] = $value
    }
    foreach ($key in $connectionKeys) {
        if (-not $values.ContainsKey($key)) { throw "EnvFile missing $key" }
        [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
    }
}

function Assert-LiveConfiguration {
    if (-not $ConfirmDisposableDatabase) {
        throw 'EnableNeo4j requires -ConfirmDisposableDatabase. The experiment writes isolated modelKey namespaces and deletes only those namespaces.'
    }
    foreach ($key in $connectionKeys) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key))) {
            throw "Live experiment requires $key"
        }
    }
    $uri = $null
    if (-not [Uri]::TryCreate($env:NEO4J_URI, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -notin @('bolt', 'bolt+s', 'bolt+ssc', 'neo4j', 'neo4j+s', 'neo4j+ssc') -or
        [string]::IsNullOrWhiteSpace($uri.Host) -or
        -not [string]::IsNullOrEmpty($uri.UserInfo)) {
        throw 'NEO4J_URI must be a valid Neo4j/Bolt endpoint without embedded credentials.'
    }
}

function Get-InputHashes {
    $paths = @(
        (Join-Path $root 'pom.xml'),
        (Join-Path $root 'ocl2cypher/pom.xml')
    )
    foreach ($folder in @('ocl2cypher/src', 'ocl2cypher/scripts', 'example')) {
        $paths += @(Get-ChildItem -LiteralPath (Join-Path $root $folder) -File -Recurse |
            ForEach-Object FullName)
    }
    @($paths | Sort-Object -Unique | ForEach-Object {
        [ordered]@{
            path = $_.Substring($root.Length + 1)
            sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash
        }
    })
}

if ($EnvFile) {
    $EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
}

Push-Location $root
try {
    if ($EnvFile) { Import-ConnectionEnvironment $EnvFile }
    if ($EnableNeo4j) { Assert-LiveConfiguration }

    New-Item -ItemType Directory -Path $runDir | Out-Null
    $before = Get-InputHashes
    $before | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $runDir 'inputs-before.json') -Encoding UTF8

    # Windows PowerShell can promote benign Git stderr warnings to terminating
    # errors while ErrorActionPreference is Stop.
    $savedErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $revision = (& git rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read repository revision' }
    $dirty = @(& git status --porcelain -- ocl2cypher example 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read repository status' }
    $ErrorActionPreference = $savedErrorPreference
    [ordered]@{
        runId = $runId
        revision = $revision
        workingTreeDirty = ($dirty.Count -gt 0)
        startedUtc = [DateTime]::UtcNow.ToString('o')
        liveNeo4j = [bool]$EnableNeo4j
        baselineSubjects = 8
        corpusSubjects = $profiles.Count
        invariantDefinitions = ($profiles.Values | Measure-Object -Sum).Sum
        allSnapshots = [bool]$AllSnapshots
        pipelineSnapshots = $expectedSnapshots
        invariantSnapshotEvaluations = $expectedInvariantEvaluations
        sourceOnlySnapshots = $expectedSourceOnly
        expectedAdmittedComparisons = $expectedComparisons
        expectedInvariantRejections = $expectedRejections
        excludedOutOfScope = @('company/Company::UniqueEmployeeNames')
        expectedInputBoundaryRejections = 3
        oracle = 'official USE evaluator exact stable-ID sets'
        target = $(if ($EnableNeo4j) { 'Core, Q interpreter and live Neo4j Cypher 5' } else { 'Core and Q interpreter; Neo4j not run' })
    } | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $runDir 'manifest.json') -Encoding UTF8

    # Java intentionally writes its version to stderr. Windows PowerShell wraps
    # that stream as NativeCommandError when ErrorActionPreference is Stop.
    $savedErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & java -version 2>&1 | Out-File -LiteralPath (Join-Path $runDir 'toolchain.txt') -Encoding UTF8
    & mvn -version 2>&1 | Out-File -LiteralPath (Join-Path $runDir 'toolchain.txt') -Encoding UTF8 -Append
    $ErrorActionPreference = $savedErrorPreference

    [Environment]::SetEnvironmentVariable('OCL2CYPHER_EXPERIMENT_LIVE',
        $(if ($EnableNeo4j) { 'true' } else { 'false' }), 'Process')
    $reports = Join-Path $runDir 'surefire'
    & mvn -pl ocl2cypher '-Dtest=ExampleCorpusExperimentIT' `
        "-Docl2cypher.allSnapshots=$($AllSnapshots.IsPresent.ToString().ToLowerInvariant())" `
        "-Docl2cypher.experimentRunId=$runId" `
        "-Docl2cypher.experimentOutput=$runDir" `
        "-Docl2cypher.testReportsDirectory=$reports" test
    if ($LASTEXITCODE -ne 0) {
        throw "Example corpus experiment failed (Maven exit $LASTEXITCODE); inspect $runDir"
    }

    $summaryPath = Join-Path $runDir 'summary.json'
    if (-not (Test-Path -LiteralPath $summaryPath)) { throw 'Experiment produced no summary.json' }
    $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
    if ($summary.status -ne 'PASS' -or
        $summary.subjects -ne $expectedSnapshots -or
        $summary.expectedInvariantEvaluations -ne $expectedInvariantEvaluations -or
        $summary.sourceOnlySnapshots -ne $expectedSourceOnly -or
        $summary.localUseQComparisonsPassed -ne $expectedComparisons -or
        $summary.localUseCoreQComparisonsPassed -ne $expectedComparisons -or
        $summary.expectedInvariantRejectionsPassed -ne $expectedRejections -or
        $summary.excludedOutOfScope -ne $expectedExclusions -or
        $summary.reviewedCsvMismatches -ne 2 -or
        $summary.boundaryRejectionsPassed -ne ($expectedRejections + 3)) {
        throw "Unexpected local evidence counts in $summaryPath"
    }
    $expectedLive = if ($EnableNeo4j) { $expectedComparisons } else { 0 }
    if ($summary.liveUseNeo4jComparisonsPassed -ne $expectedLive) {
        throw "Expected $expectedLive live comparisons, found $($summary.liveUseNeo4jComparisonsPassed)"
    }

    $after = Get-InputHashes
    $after | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $runDir 'inputs-after.json') -Encoding UTF8
    if (($before | ConvertTo-Json -Depth 5 -Compress) -ne
        ($after | ConvertTo-Json -Depth 5 -Compress)) {
        throw 'Experiment inputs changed during execution.'
    }

    [ordered]@{
        status = $(if ($EnableNeo4j) { 'LOCAL_AND_LIVE_PASS' } else { 'LOCAL_PASS_LIVE_NOT_RUN' })
        completedUtc = [DateTime]::UtcNow.ToString('o')
        evidenceDirectory = $runDir
        note = 'Finite correctness evidence; timings are audit metadata, not a performance benchmark.'
    } | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $runDir 'outcome.json') -Encoding UTF8
    Write-Host "Experiment PASS. Evidence directory: $runDir"
} catch {
    if (Test-Path -LiteralPath $runDir) {
        [ordered]@{
            status = 'FAILED_OR_INCOMPLETE'
            completedUtc = [DateTime]::UtcNow.ToString('o')
            message = $_.Exception.Message
        } | ConvertTo-Json |
            Set-Content -LiteralPath (Join-Path $runDir 'outcome.json') -Encoding UTF8
    }
    throw
} finally {
    foreach ($key in $connectionKeys) {
        [Environment]::SetEnvironmentVariable($key, $savedConnections[$key], 'Process')
    }
    [Environment]::SetEnvironmentVariable('OCL2CYPHER_EXPERIMENT_LIVE',
        $savedLive, 'Process')
    Pop-Location
}
